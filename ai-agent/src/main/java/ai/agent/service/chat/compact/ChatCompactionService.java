package ai.agent.service.chat.compact;

import ai.agent.entity.chat.ConversationSummary;
import ai.agent.service.prompt.PromptKind;
import ai.agent.service.prompt.PromptService;
import ai.agent.service.token.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * <b>把"删旧对话"换成"压旧对话"</b> —— 对话历史滚动摘要的唯一实现。
 *
 * <h2>它替代的是什么</h2>
 * 原来历史变长只有一种处理：删。而删掉的消息在下游没有任何留底
 * （{@code InMemoryChatMemoryRepository} 是堆内 Map，{@code saveAll} 是整段替换），
 * 于是"用户当时到底说了什么"变成一个不可回答的问题 —— 而且它不报错。
 * 现在被挤出去的那一段由模型压成一份摘要，摘要进 prompt，原文留在存储里。
 *
 * <h2>压缩单位为什么是「一整轮」而不是「一条消息」</h2>
 * 只压到某条 assistant 回答的中间，摘要里就会出现"用户问了但没答"或"答了但没有问"的
 * 半截对话，而模型会把摘要当事实读。所以切点只能落在一轮的边界上：
 * 能安全作为"被压掉的最后一条"的，是一条 assistant 消息（它的用户提问就在它前面）。
 * 落不到边界上就少压几条 —— 宁可这一段不压，也不切出半轮对话。
 *
 * <h2>摘要为什么是「增量合并」的</h2>
 * 每次压缩的输入是【已有摘要】+【这次被挤出的对话】，输出是<b>一份</b>新摘要
 * （{@link ConversationSummary#mergedInto}）。把历次摘要堆起来走不通：
 * 摘要会越堆越长，最后比原话还长 —— 那就不是压缩了。
 *
 * <h2>压不动的时候怎么办（三次防线，都刻意选保守的那一侧）</h2>
 * <ol>
 *   <li><b>模型没给出可用摘要</b>（抛异常 / 空 / 只有围栏 / 短到明显不是摘要）→
 *       这次不压，返回一句可以让调用方塞进系统消息的兜底提示。旧摘要保持有效；</li>
 *   <li><b>摘要比它替代的原文还长</b> → 同样不压。压缩把 prompt 弄长了是纯亏损，
 *       而它看起来"一切正常"（摘要确实生成了）；</li>
 *   <li><b>压完仍超预算</b> → 如实返回 {@link Outcome#overBudget()}，由调用方决定
 *       是拒绝还是硬发。这一层不假装成功。</li>
 * </ol>
 *
 * <h2>它不做的事</h2>
 * 不删任何消息、不回写存储、不改系统提示词。它只回答：
 * <b>"要发给模型的那份历史，应该长什么样"</b>。
 */
@Service
public class ChatCompactionService {

	private static final Logger log = LoggerFactory.getLogger(ChatCompactionService.class);

	/** 摘要短于这个字符数就判定"不是摘要"：正常摘要不可能只有十来个字。 */
	private static final int MIN_SUMMARY_CHARS = 12;

	/** 模型爱加的代码围栏 —— 剥掉，不然它会进 prompt 并影响后续轮次的读法。 */
	private static final String FENCE = "```";

	private final ChatSummaryStore store;

	private final TokenEstimator estimator;

	private final PromptService prompts;

	private final ObjectProvider<ChatModel> chatModels;

	public ChatCompactionService(ChatSummaryStore store, TokenEstimator estimator, PromptService prompts,
			ObjectProvider<ChatModel> chatModels) {
		this.store = store;
		this.estimator = estimator;
		this.prompts = prompts;
		this.chatModels = chatModels;
	}

	/**
	 * 这次真正发给模型的消息。
	 *
	 * @param messages 要发的完整列表（摘要 + 保留的那几条）；没压缩过时就是原样一份
	 * @param summary  当前生效的摘要（来自存储）；没压缩过时为空
	 */
	public record ConversationView(List<Message> messages, Optional<ConversationSummary> summary) {
	}

	/**
	 * 一次压缩尝试的结果。
	 *
	 * @param view              实际要发给模型的消息
	 * @param summary           本次生效的摘要
	 * @param compactedMessages 这次被压掉的消息条数（0 = 什么都没压）
	 * @param overBudget        压完之后估算值仍超预算
	 * @param needsModelNudge   非 null 时是"压不动"的说明，调用方可以把它塞进系统消息
	 */
	public record Outcome(ConversationView view, Optional<ConversationSummary> summary, int compactedMessages,
			boolean overBudget, String needsModelNudge) {

		/** 这次有没有真的压掉东西。 */
		public boolean compacted() {
			return compactedMessages > 0;
		}
	}

	/**
	 * 忘掉一条会话的摘要（会话被清空时用）。
	 *
	 * <p>与"历史被清空"必须成对发生：只清历史会让摘要留着指向一段不存在的对话，
	 * 而那种状态是静默的 —— 下一次拼 prompt 时那段摘要凭空出现，模型会把它
	 * 当成"用户之前说过的话"。
	 */
	public void forget(String conversationId) {
		store.delete(conversationId);
	}

	/**
	 * 按已生效的摘要构造要发出去的历史。
	 *
	 * <p><b>纯读、不发请求</b>：这是 {@code ChatMemory.get()} 走的那条路 ——
	 * 它在 advisor 的 {@code before()} 阶段被同步调用，不可能在那里花一次模型调用。
	 * 位置靠 {@link ConversationSummary#coveredTurns()} 数出来，所以会话被重建、
	 * 消息被摘掉之后依然指得准。
	 *
	 * @param conversationId 会话 id
	 * @param full           存储里的完整历史
	 * @return 摘要 + 未被覆盖的那些消息（没有摘要时原样返回）
	 */
	public ConversationView view(String conversationId, List<Message> full) {
		List<Message> history = full == null ? List.of() : full;
		Optional<ConversationSummary> found = store.find(conversationId);
		if (found.isEmpty() || history.isEmpty()) {
			return new ConversationView(history, found);
		}
		ConversationSummary summary = found.get();
		int firstKept = indexAfterCoveredTurns(history, summary.coveredTurns());
		if (firstKept < 0) {
			// 存储里的历史比摘要声称覆盖的还短 —— 只可能是会话被清空之后又重建了。
			// 这时摘要指向的内容已经不在列表里，用它拼出来的 prompt 会出现
			// "摘要说了一堆、原文里找不到出处"的错位。所以放弃这张摘要，
			// 只用真实存在的历史；删掉它让下一次压缩从干净状态重新开始。
			log.warn("会话 {} 的历史（{} 条）比摘要覆盖的 {} 轮还短：这份摘要已经指不到它描述的内容，"
					+ "按【没有摘要】继续，并删掉它以便重新压缩", conversationId, history.size(),
					summary.coveredTurns());
			store.delete(conversationId);
			return new ConversationView(history, Optional.empty());
		}
		return new ConversationView(join(summary, history.subList(firstKept, history.size())), Optional.of(summary));
	}

	/**
	 * 超预算时压缩历史：切出最旧的若干整轮 → 合并进摘要 → 返回要发的那一份。
	 *
	 * <p>切多少由预算算出来，不是固定条数：{@code baseTokens}（系统提示词 + 本轮提问 +
	 * 请求开销）每个请求都不一样，固定条数在"提问很长"的那一轮会切得不够。
	 *
	 * <h2>两种失败的分界</h2>
	 * "历史本身不够压"（切点落不到轮边界上）与"压完还超"是两件事，返回里分开表达：
	 * 前者由调用方拒绝，后者由调用方决定要不要硬发 —— 混成一个"失败"会让
	 * "该拒绝的没拒绝"和"该发的没发"同时发生。
	 */
	public Outcome compact(CompactionRequest request) {
		String conversationId = request.conversationId();
		List<Message> full = request.history();

		// 【为什么这里要单独判一次"没超预算"】
		// 下面是"按预算从后往前切"，而切法本身不知道"其实一格都不用切"：
		// 预算充足时它会把整个历史都装下、但受 minMessages 那条底线拦住，
		// 于是返回一个"只保留最后 N 条"的切点 —— 看起来像"压一点"，
		// 实际是**白白花一次模型调用**去压一段本来装得下的对话。
		// 这个 bug 的症状很隐蔽：功能"能用"，只是每次都多花一次钱。
		if (request.baseTokens() + estimateAll(full) <= request.allowance()) {
			return new Outcome(new ConversationView(full, store.find(conversationId)), store.find(conversationId), 0,
					false, null);
		}

		Optional<ConversationSummary> existing = store.find(conversationId);
		int alreadyCovered = existing.map(ConversationSummary::coveredTurns).orElse(0);
		int liveStart = alreadyCovered == 0 ? 0 : indexAfterCoveredTurns(full, alreadyCovered);
		if (liveStart < 0) {
			// 与 view() 同一条判据：摘要指不到了，丢掉重来
			log.warn("会话 {} 的历史与摘要的覆盖轮数对不上：丢掉旧摘要，从零重新压", conversationId);
			store.delete(conversationId);
			existing = Optional.empty();
			liveStart = 0;
		}

		int evictEnd = evictionEnd(request, liveStart);
		if (evictEnd <= liveStart) {
			// 没有可压的整轮（或窗口本身还没满）
			return new Outcome(new ConversationView(full, existing), existing, 0, overBudget(request, full), null);
		}

		List<Message> evicted = List.copyOf(full.subList(liveStart, evictEnd));
		int evictedTokens = estimateAll(evicted);
		String merged = summarize(existing, evicted);
		if (merged == null) {
			return new Outcome(new ConversationView(full, existing), existing, 0, overBudget(request, full),
					nudge(full, evicted));
		}
		if (estimator.estimateMessage(merged) >= evictedTokens) {
			log.warn("会话 {} 的摘要（{} 字符）不比它替代的 {} 条消息（约 {} tokens）短，放弃这次压缩："
					+ "压缩把 prompt 弄长是纯亏损，而且它看起来一切正常",
					conversationId, merged.length(), evicted.size(), evictedTokens);
			return new Outcome(new ConversationView(full, existing), existing, 0, overBudget(request, full),
					nudge(full, evicted));
		}

		int additionalTurns = countUserMessages(evicted);
		ConversationSummary updated = existing
				.map(previous -> previous.mergedInto(additionalTurns, merged, request.provider()))
				.orElseGet(() -> new ConversationSummary(conversationId, additionalTurns, merged, 1,
						request.provider()));
		store.save(updated);

		List<Message> kept = full.subList(evictEnd, full.size());
		int compacted = evictEnd - liveStart;
		boolean stillOver = request.baseTokens() + estimator.estimateMessage(merged) + estimateAll(kept)
				> request.allowance();
		log.info("会话 {} 压缩了 {} 条消息（{} 轮用户发言，约 {} tokens → 摘要 {} 字符，累计压缩 {} 次）："
				+ "原文仍留在对话记忆里，只影响这次发出去的 prompt", conversationId, compacted, additionalTurns,
				evictedTokens, merged.length(), updated.turnsCompacted());
		if (stillOver) {
			log.warn("会话 {} 压缩之后估算值仍超预算（保留 {} 条）：这次请求会被硬发出去，上游可能报超窗"
					+ "或静默截断 —— 要调的是 ai.chat.budget.context-window 与 "
					+ "ai.chat.budget.min-history-messages 这一对配置", conversationId, kept.size());
		}
		return new Outcome(new ConversationView(join(updated, kept), Optional.of(updated)), Optional.of(updated),
				compacted, stillOver, null);
	}

	// ------------------------------------------------------------------
	//  切分
	// ------------------------------------------------------------------

	/**
	 * 从哪一条开始保留：返回的下标之前的那些整轮都会被挤进摘要。
	 *
	 * <p>算法分两步，顺序不能反：
	 * <ol>
	 *   <li><b>先按预算从后往前累加</b>，直到"已经占掉的 + 还没挤出去的"落进允许额度里，
	 *       同时不越过"至少保留 {@code minMessages} 条"这条底线；</li>
	 *   <li><b>再把切点对齐到轮边界</b>：被挤掉的最后一条必须是 assistant 消息。
	 *       落在别处就少挤一条（{@code index--}）—— 这个方向永远安全，因为它只是压得少一点。</li>
	 * </ol>
	 *
	 * <p>返回 {@code liveStart} 表示没挤出任何整轮：调用方据此报"历史不够压"。
	 */
	private int evictionEnd(CompactionRequest request, int liveStart) {
		List<Message> full = request.history();
		int floor = Math.max(liveStart, full.size() - Math.max(0, request.minMessages()));
		int accumulated = 0;
		int index = full.size();
		while (index > floor) {
			accumulated += estimator.estimateMessage(textOf(full.get(index - 1)));
			index--;
			if (request.baseTokens() + accumulated >= request.allowance()) {
				break;
			}
		}
		if (index <= liveStart) {
			return liveStart;
		}
		// 边界对齐（见方法注释）：被挤掉的最后一条必须是 assistant 消息。
		// 往小走是安全方向 —— 只是这一段不压，而不是切出半轮对话
		while (index > liveStart && !isTurnBoundary(full.get(index - 1))) {
			index--;
		}
		return index;
	}

	/**
	 * 一轮的边界：这一条是 assistant 消息（它的用户提问就在它前面，成对）。
	 *
	 * <p>用"是不是 assistant"而不是"下一条是不是 user"：会话里可能夹着别的角色的消息
	 * （工具结果、注入的系统消息），而"被挤掉的最后一条是 assistant"
	 * 这个说法在任何角色组合下都成立。
	 */
	static boolean isTurnBoundary(Message message) {
		return message != null && message.getMessageType() == MessageType.ASSISTANT;
	}

	/**
	 * 摘要覆盖了 {@code coveredTurns} 轮用户发言之后，第一条该保留的消息的下标。
	 *
	 * <p>规则：跳过 {@code coveredTurns} 条用户消息，<b>返回下一条用户消息的位置</b> ——
	 * 那一条就是"还没被压过的第一轮"的提问。
	 *
	 * <p>为什么要"再往前找到下一条用户消息"而不是直接返回紧随其后的那一条：
	 * 被覆盖的那一轮里，用户消息后面可能跟着多条 assistant / 工具消息。直接返回"第
	 * coveredTurns+1 条消息"会把那一轮的后半截留在 prompt 里（有答没有问）。
	 * 返回下一条<b>用户</b>消息则天然对齐在轮边界上。
	 *
	 * @return 下标，或 -1 表示历史里的用户消息不够（摘要指不到它描述的内容）
	 */
	static int indexAfterCoveredTurns(List<Message> full, int coveredTurns) {
		int seen = 0;
		for (int i = 0; i < full.size(); i++) {
			if (full.get(i).getMessageType() != MessageType.USER) {
				continue;
			}
			seen++;
			if (seen > coveredTurns) {
				return i;
			}
		}
		return -1;
	}

	static int countUserMessages(List<Message> messages) {
		int count = 0;
		for (Message message : messages) {
			if (message != null && message.getMessageType() == MessageType.USER) {
				count++;
			}
		}
		return count;
	}

	private boolean overBudget(CompactionRequest request, List<Message> messages) {
		return request.baseTokens() + estimateAll(messages) > request.allowance();
	}

	// ------------------------------------------------------------------
	//  模型调用
	// ------------------------------------------------------------------

	/**
	 * 把【旧摘要】+【被挤出的对话】合成一份新摘要；压不动时返回 {@code null}。
	 *
	 * <h2>为什么直接调 {@code ChatModel}，不走 {@code ChatClient}</h2>
	 * {@code ChatClient} 上挂着 {@code MessageChatMemoryAdvisor}，而那条 advisor 会在
	 * {@code before()} 里读对话记忆 —— 于是"为了压缩历史而发的那次请求"会带上
	 * 被压缩的历史，并且反过来再触发一次压缩。用 {@code ChatModel} 是绕开这条环的办法，
	 * 与 {@code OpenAiModelTurn} 用 {@code ChatModel} 而不是 {@code ChatClient} 同一条理由。
	 *
	 * <p>提示词正文来自 {@link PromptKind#CHAT_COMPACTION}（受版本管理，可从界面换版本），
	 * 这里只负责拼装与剥围栏。
	 */
	private String summarize(Optional<ConversationSummary> existing, List<Message> evicted) {
		ChatModel model = chatModels.getIfAvailable();
		if (model == null) {
			log.warn("容器里没有 ChatModel，跳过这次对话压缩");
			return null;
		}
		String system = prompts.currentContent(PromptKind.CHAT_COMPACTION);
		StringBuilder user = new StringBuilder();
		user.append("【已有摘要】\n");
		user.append(existing.map(ConversationSummary::summary).orElse("（这是第一次压缩，没有已有摘要）"));
		user.append("\n\n【被挤出去的旧对话】\n");
		for (Message message : evicted) {
			user.append('[').append(roleLabel(message)).append("] ").append(textOf(message)).append('\n');
		}
		try {
			String raw = model.call(new Prompt(List.of(new SystemMessage(system),
					new UserMessage(user.toString())))).getResult().getOutput().getText();
			return sanitize(raw);
		}
		catch (RuntimeException e) {
			// 压缩失败不该让这次对话失败：调用方会退回"没有摘要"的那条路
			log.warn("对话压缩的模型调用失败：{}", e.toString());
			return null;
		}
	}

	/** 剥围栏、去空白，并判定"这到底是不是一份摘要"。判定不过返回 {@code null}。 */
	static String sanitize(String raw) {
		if (raw == null) {
			return null;
		}
		String text = raw.strip();
		if (text.startsWith(FENCE)) {
			// 只吃掉第一行（``` 或 ```markdown）与末尾那一个围栏标记。
			// ⚠️ 第一行没有换行时**不能**把整段清空：那种输入是"以 ``` 开头的摘要"，
			// 不是"一个围栏块"。清空的表现是"每次压缩都失败"，而失败原因看不出来
			int firstBreak = text.indexOf('\n');
			if (firstBreak >= 0) {
				text = text.substring(firstBreak + 1);
				if (text.endsWith(FENCE)) {
					text = text.substring(0, text.length() - FENCE.length());
				}
				text = text.strip();
			}
		}
		return text.length() < MIN_SUMMARY_CHARS ? null : text;
	}

	// ------------------------------------------------------------------
	//  兜底：压不动时让模型自己保重点
	// ------------------------------------------------------------------

	/**
	 * 压不动时塞进系统消息的那一句话。
	 *
	 * <p><b>为什么要有这条兜底</b>：压缩失败之后这次请求要么被拒、要么带着一整个超预算的
	 * 历史硬发（后者可能被上游静默截断 —— 那是最坏的形态，因为用户看到的是一个正常回答）。
	 * 而"早期内容没能压进摘要"这句话本身几乎不花 token，却能让模型在被截断的情况下
	 * 至少不去凭猜测补全缺失的部分。
	 */
	private static String nudge(List<Message> full, List<Message> evicted) {
		return "（本次对话更早的内容约有 %d 条没能压缩进摘要 —— 如果问题涉及那部分，"
				.formatted(evicted.size())
				+ "如实说明你看不到，不要凭猜测补全。当前能看到最近 %d 条。）"
						.formatted(full.size() - evicted.size());
	}

	// ------------------------------------------------------------------
	//  小工具
	// ------------------------------------------------------------------

	private static List<Message> join(ConversationSummary summary, List<Message> kept) {
		List<Message> messages = new ArrayList<>(kept.size() + 1);
		messages.add(new SystemMessage(summaryBlock(summary)));
		messages.addAll(kept);
		return List.copyOf(messages);
	}

	/**
	 * 摘要进 prompt 时的外壳。
	 *
	 * <p>三件事必须写进去，缺一件都会让模型读错这段文本：
	 * <ol>
	 *   <li><b>它是摘要而不是用户发言</b> —— 否则模型会以为用户在跟它说这些；</li>
	 *   <li><b>覆盖了哪几轮</b> —— 让"更早的对话你有没有"这个问题有据可答；</li>
	 *   <li><b>不要复述它</b> —— 复述会把它又抄一遍进回答，白花输出 token。</li>
	 * </ol>
	 */
	static String summaryBlock(ConversationSummary summary) {
		return """
				【历史对话摘要（由系统自动压缩，覆盖本会话最早的 %d 轮用户发言）】
				把它当作已经确立的背景，直接在此基础上继续；不要向用户复述这段摘要，也不要提"摘要"这件事。
				如果本次问题涉及摘要里没有的内容，如实说明你看不到那部分，不要凭猜测补全。

				%s"""
				.formatted(summary.coveredTurns(), summary.summary());
	}

	/** 角色的中文写法：模型读的是人话，不是枚举名。 */
	private static String roleLabel(Message message) {
		if (message == null) {
			return "未知";
		}
		return switch (message.getMessageType()) {
			case USER -> "用户";
			case ASSISTANT -> "助手";
			case SYSTEM -> "系统";
			case TOOL -> "工具";
		};
	}

	static String textOf(Message message) {
		if (message == null) {
			return "";
		}
		String text = message.getText();
		return text == null ? "" : text;
	}

	private int estimateAll(List<Message> messages) {
		int total = 0;
		for (Message message : messages) {
			total += estimator.estimateMessage(textOf(message));
		}
		return total;
	}
}

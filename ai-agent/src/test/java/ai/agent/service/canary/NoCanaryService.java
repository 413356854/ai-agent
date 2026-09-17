package ai.agent.service.canary;

import ai.agent.entity.canary.CanaryDecision;
import ai.agent.service.prompt.PromptKind;
import ai.agent.service.prompt.PromptService;
import ai.agent.entity.prompt.PromptVersion;

import java.util.List;
import java.util.Optional;

/**
 * <b>不做灰度分流的替身</b>（测试用）。
 *
 * <h2>为什么需要它，而不是"到处传一个真的灰度服务"</h2>
 * {@code AgentLoop} 现在在每次运行开始时问一次灰度服务"这一次走哪一版"。
 * 而绝大多数测试（循环的停止条件、恢复、HITL、并发闸门）与灰度<b>毫无关系</b> ——
 * 让它们各自去起一个 H2 库、造一个版本、开一次灰度，会把
 * "验证循环"变成"验证循环 + 验证灰度存储"，而后者失败时前者的结论也跟着不可信。
 *
 * <p>这与 {@code ClasspathPromptService}（提示词存储的替身）是同一条做法，
 * 而且它<b>同时是一份活的文档</b>：它把"没有灰度时应该发生什么"写成了可执行的代码 ——
 * 直接读当前生效那一版、不算桶、variant 记空串。
 *
 * <h2>⚠️ 它证明不了什么</h2>
 * 它证明不了分流真的按比例发生（那要 {@code CanaryTest} 用真库验证），
 * 也证明不了"命中候选版时正文换了"（那要端到端测试）。
 * 这一点必须写在这里：一个替身最危险的用法，是被当成"功能已经验证过了"。
 */
public final class NoCanaryService implements CanaryService {

	private final PromptService prompts;

	public NoCanaryService(PromptService prompts) {
		this.prompts = prompts;
	}

	@Override
	public Optional<Canary> current(PromptKind kind) {
		return Optional.empty();
	}

	@Override
	public Optional<Canary> previous(PromptKind kind) {
		return Optional.empty();
	}

	@Override
	public Canary start(PromptKind kind, int candidateVersion, int weightPercent, String note, String by) {
		throw new CanaryRejectedException("这是测试替身：它不做灰度（见 NoCanaryService 的注释）");
	}

	@Override
	public Canary stop(PromptKind kind, String endReason, String by) {
		throw new CanaryRejectedException("这是测试替身：它不做灰度（见 NoCanaryService 的注释）");
	}

	@Override
	public CanaryDecision decide(PromptKind kind, String runId) {
		// 没有灰度：直接读当前生效那一版，variant 空串（= 没参与这次实验）
		return CanaryDecision.stable(prompts.activeVersion(kind).map(PromptVersion::version).orElse(0),
				prompts.currentFingerprint(kind), "测试替身：没有进行中的灰度");
	}

	@Override
	public Optional<String> candidateContent(PromptKind kind, int version) {
		return Optional.empty();
	}

	@Override
	public Split observation(PromptKind kind, int days) {
		return new Split(0, 0, 0, 0, 0, 0, List.of());
	}

}

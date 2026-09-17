package ai.agent.service.prompt;

import org.springframework.core.io.AbstractResource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 把"当前生效的提示词"包装成一个 {@link org.springframework.core.io.Resource}。
 *
 * <h2>它为什么存在，以及它的边界（这一段比代码重要）</h2>
 * 阶段一的 {@code ChatClientConfig} 用 {@code .defaultSystem(Resource)} 注入系统提示词。
 * 那个写法有一个不显眼的性质：{@code ChatClientBuilder} 在<b>构建 Client 的那一刻</b>
 * 就调用了 {@code Resource.getContentAsString()}（用 {@code javap} 核过
 * {@code DefaultChatClientBuilder.defaultSystem(Resource, Charset)} 的字节码），
 * 也就是说 —— <b>一个"每次读都返回新内容"的 Resource 并不能让 ChatClient 热更新</b>，
 * 内容在启动时就被取走了。
 *
 * <p>所以这个类<b>不是</b>让 {@code ChatClient} 热更新的手段，它的两个真实作用是：
 * <ol>
 *   <li><b>消除第二份真相</b>：启动时 ChatClient 取到的内容来自库里当前生效的那一版
 *       （而不是"另一个 classpath 文件"）。若不用它，就会出现"库里的 active 是 v3、
 *       而 ChatClient 用的是出厂文件"—— 两个来源，且不报错；</li>
 *   <li><b>让"启动时的快照"这件事可被观察</b>：{@code getInputStream()} 每次读都去问服务，
 *       所以任何一处"读一次就留着用"的调用点都会暴露在测试里，
 *       而不是变成一个"看起来能用、改了不生效"的黑洞。</li>
 * </ol>
 *
 * <p><b>真正的热更新走调用点</b>：{@code ChatServiceImpl} / {@code StructuredChatServiceImpl}
 * 每次请求都显式调用 {@code .system(registry.currentContent(CHAT_SYSTEM))}，
 * 于是"下一轮对话就生效"。{@code AgentLoop} 本来就是每次运行读一次，天然热更新。
 *
 * <h2>为什么是 AbstractResource 而不是实现整个 Resource 接口</h2>
 * {@code Resource} 有十几个方法，而热更新真正需要的只有 {@code getInputStream} 与
 * {@code exists}。继承 {@code AbstractResource} 之后其余方法走默认实现，
 * 这个类就只剩"一件事" —— 而一个只剩下一个职责的类，才可能被读懂。
 */
public class PromptContextResource extends AbstractResource {

	private final PromptService service;

	private final PromptKind kind;

	public PromptContextResource(PromptService service, PromptKind kind) {
		this.service = Objects.requireNonNull(service, "service");
		this.kind = Objects.requireNonNull(kind, "kind");
	}

	@Override
	public String getDescription() {
		return "prompt:" + kind.defaultValue() + "（当前生效版本，来自版本库）";
	}

	@Override
	public String getFilename() {
		// 沿用 classpath 文件名：它会被日志与异常信息打印出来，
		// 换一个名字会让"提示词读取失败"的报错看起来像另一个东西坏了
		return kind.classpathLocation().substring(kind.classpathLocation().lastIndexOf('/') + 1);
	}

	@Override
	public InputStream getInputStream() {
		// 每次都重新问服务：**不缓存**。缓存在这里等于把热更新关掉，
		// 而且关得非常安静（没有任何日志、没有任何报错）
		return new ByteArrayInputStream(service.currentContent(kind).getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public boolean exists() {
		// 永远为真：兜底链保证"库空 → classpath → 抛异常"，
		// 能走到这里就说明有一份可用的内容（真读不到时 getInputStream 会抛，不靠 exists 表达）
		return true;
	}

	@Override
	public long contentLength() {
		return service.currentContent(kind).getBytes(StandardCharsets.UTF_8).length;
	}

}

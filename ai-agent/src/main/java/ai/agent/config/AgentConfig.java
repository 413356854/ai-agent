package ai.agent.config;

import ai.agent.agent.ModelTurn;
import ai.agent.agent.OpenAiModelTurn;
import ai.agent.agent.limit.ConcurrencyGate;
import ai.agent.agent.limit.LimitedModelTurn;
import ai.agent.tool.ToolRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 循环的装配（阶段三）。
 *
 * <h2>为什么 {@link ModelTurn} 要留一个"可被替换"的出口</h2>
 * 这是本项目里<b>最值得保留的一个测试接缝</b>。
 * {@code AgentLoop} 的全部逻辑（三重上限、重复检测、回填顺序、自愈路径）都挂在
 * "模型下一轮会返回什么"上，而真模型<b>不会配合</b>：
 * 它可能一次就答对，于是"工具失败后改对"这条最重要的路径永远没被跑到。
 *
 * <p>{@code @ConditionalOnMissingBean} 让测试可以用
 * {@code @SpringBootTest} + 一个 {@code @TestConfiguration} 提供
 * {@code ScriptedModelTurn}，从而确定性地跑完整条循环 ——
 * 而生产环境不受影响（没人为干预时就装配真实的 OpenAI 适配器）。
 *
 * <h2>为什么不把 {@link OpenAiModelTurn} 直接标 {@code @Component}</h2>
 * 标了之后容器里就<b>一定</b>有两个 {@code ModelTurn}（真实那个 + 测试那个），
 * 注入点会因歧义而启动失败。用条件装配把"谁赢"这件事交给容器的既有语义，
 * 而不是靠测试里写 {@code @Primary} 去压制 —— 后者在有人忘记加时
 * 会静默注入真实适配器（然后在测试里发真请求），那是最坏的一种测试失败。
 */
@Configuration
@EnableConfigurationProperties({ AgentLoopProperties.class, AgentStoreProperties.class,
		AgentConcurrencyProperties.class })
public class AgentConfig {

	/*
	  这里**刻意没有** AgentLoopProperties 的 @Bean 方法。

	  踩到的坑：@EnableConfigurationProperties 已经注册了一个
	  AgentLoopProperties bean（bean 名是 `ai.agent.loop-ai.agent.config.AgentLoopProperties`，
	  因为它是按 @ConfigurationProperties 前缀命名的），而当时我又写了一个
	  `@Bean @ConditionalOnMissingBean AgentLoopProperties agentLoopProperties()`。
	  @ConditionalOnMissingBean 按**类型与名字**一起判，两个名字不同 → 判定"没有" → 又注册一个
	  叫 `agentLoopProperties` 的 bean。容器里于是有两个同类型 bean，
	  AgentLoop 的构造注入直接失败：`expected single matching bean but found 2`。

	  教训：**@EnableConfigurationProperties 与 @Bean 只能有一个负责注册**。
	  默认值也不用在这里给 —— 记录组件的紧凑构造器保证每个字段都有绑定值，
	  绑定来源是 application.properties 里的 ai.agent.loop.*（见那边的注释）。
	*/

	/**
	 * 真实模型适配器。<b>外面再套一层并发闸门</b>（阶段三第 3 件）。
	 *
	 * <h2>为什么包装发生在这里，而不是在 AgentLoop 里</h2>
	 * 这个 bean 是"所有模型调用的唯一入口"：循环、将来的诊断端点、以及任何直接用
	 * {@code ModelTurn} 的地方都会拿到它。在这里包一层，等于<b>用一个位置覆盖所有调用点</b>——
	 * 而在循环里加锁只能保护循环那条路径（结构化输出的回灌轮、诊断端点就不会被保护到）。
	 *
	 * <p>顺带一个设计上的好处：{@code AgentLoop} 完全不知道限流的存在，
	 * 它的 15 个测试也不需要为此起一个信号量。
	 *
	 * <p>注意顺序：{@code LimitingModelTurn} 包在 {@code OpenAiModelTurn} <b>外面</b>。
	 * 反过来的话（闸门在内层），每次调用都会先建好请求、走到 HTTP 那一层才被拦，
	 * 白做一遍准备；更重要的是"在途"的统计口径会变成"已经进到适配器内部的"，
	 * 而不是"准备发出去的" —— 后者才是我们想限制的东西。
	 */
	@Bean
	@ConditionalOnMissingBean(ModelTurn.class)
	ModelTurn openAiModelTurn(ChatModel chatModel, ToolRegistry registry,
			AgentConcurrencyProperties concurrencyProperties) {
		ModelTurn real = new OpenAiModelTurn(chatModel, registry);
		ConcurrencyGate gate = new ConcurrencyGate("model", concurrencyProperties.maxConcurrentModelRequests(),
				concurrencyProperties.acquireTimeoutMs());
		return new LimitedModelTurn(real, gate);
	}

}

package ai.agent.prompt;

/**
 * 提示词存储层的失败。
 *
 * <p>与 {@code AgentStoreException} 同一条理由：因为本项目的存储层是<b>手写 JDBC</b>
 * （没有 {@code spring-jdbc} 就没有 {@code DataAccessException} 的异常翻译），
 * 所以自己包一层，让上层不必依赖 {@code java.sql}。
 *
 * <p>它是 {@link RuntimeException}：提示词读不出来时**不该继续跑** ——
 * 一个没有系统提示词的 Agent 会用模型的默认人格回答，
 * 而那看起来像"模型不听话"，实际是部署问题（与 {@code AgentLoop} 里那条注释同一个理由）。
 */
public class PromptStoreException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public PromptStoreException(String message) {
		super(message);
	}

	public PromptStoreException(String message, Throwable cause) {
		super(message, cause);
	}

}

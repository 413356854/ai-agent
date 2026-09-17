package ai.agent.service.prompt;

/**
 * 提示词写入被校验拒绝（阶段四第 3 件）。
 *
 * <p>它与"存储出错"是<b>两件事</b>，端点也必须分开表达：
 * <ul>
 *   <li>这一类是 <b>400</b>：调用方写的内容有问题，改内容就能过；</li>
 *   <li>{@link PromptStoreException} 是 <b>500</b>：内容没问题，是库或部署出了问题。</li>
 * </ul>
 * 混成一个 500 的后果是：使用者会以为"系统坏了"，于是重试 ——
 * 而重试同一个内容永远不会成功。这与"上游 401 重试没有意义"是同一类判断
 * （见 {@code AgentFailureClassifier} 里那条）。
 */
public class PromptRejectedException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final transient PromptValidator.Result result;

	public PromptRejectedException(String message, PromptValidator.Result result) {
		super(message);
		this.result = result;
	}

	/** 被拒时的完整校验结论（端点把它原样返回，页面才能指出"哪一条没过、具体数字是多少"）。 */
	public PromptValidator.Result result() {
		return result;
	}

}

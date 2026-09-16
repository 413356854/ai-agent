package ai.agent.prompt;

/**
 * 一次"存新版本"的结果：写入的那一版 + 当时的校验结论（阶段四第 3 件）。
 *
 * <h2>为什么把校验结论一起带回来，而不是让端点再算一次</h2>
 * 端点上再调一次 {@code PromptValidator} 看起来无害，但它会让<b>校验跑两遍</b>，
 * 而两遍之间如果"当前生效版本"变了（有人在另一个标签页激活了新版本），
 * 第二遍拿到的"上一版"就不是第一遍那一份 —— 于是接口返回的校验结论
 * 与**实际写入时用的判据**不一致。这种不一致不报错，只是页面上的警告对不上实际情况。
 * 所以：谁写入、谁返回结论。
 *
 * @param saved  真正落库的那一版
 * @param result 写入前那一次校验的完整结论（含通过项与警告项）
 */
public record PromptChange(PromptVersion saved, PromptValidator.Result result) {
}

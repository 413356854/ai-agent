package ai.agent.service.support.chat.structured;

import ai.agent.config.ChatCallProperties;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * 置信度下界的校验器，阈值从配置读（{@code ai.chat.structured.min-confidence}）。
 *
 * <p><b>为什么不用注解写死</b>：{@code @DecimalMin("0.0")} 的阈值是编译期常量，
 * 而"多低的自评算不合格"是<b>业务策略</b>，不是技术约束 —— 内部知识问答可能要求 0.6 以上，
 * 头脑风暴类场景 0.1 也照样用。写死在注解里意味着每次调整都要改代码重新发布。
 *
 * <p>顺带得到一个很实用的能力：<b>把阈值调高就能确定性复现校验失败</b>。
 * 调试回灌链路时，"让线上模型恰好输出一个越界值"是很难的，
 * 但把它设成 0.99 就能稳定触发 —— 这是这条链路唯一可靠的验证手段。
 *
 * <p><b>构造器注入为什么能work</b>：Spring Boot 自动配置的校验器用的是
 * {@code SpringConstraintValidatorFactory}，它会从容器里取校验器实例，
 * 所以这里的 {@link ChatCallProperties} 能被正常注入。
 * 但这也意味着：<b>脱离 Spring 用 {@code Validation.buildDefaultValidatorFactory()}
 * 会失败</b>（找不到无参构造器）—— 测试里必须传 Spring 的 Validator，
 * 或者用 {@code ConstraintValidatorFactory} 手工装配。
 */
public class MinConfidenceValidator implements ConstraintValidator<MinConfidence, Double> {

	private final ChatCallProperties properties;

	public MinConfidenceValidator(ChatCallProperties properties) {
		this.properties = properties;
	}

	@Override
	public boolean isValid(Double value, ConstraintValidatorContext context) {
		if (value == null) {
			// null 交给 @NotNull 去报，两个校验器各管一件事，避免同一问题报两条错
			return true;
		}
		return value >= properties.getStructured().getMinConfidence();
	}

}

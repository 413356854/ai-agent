package ai.agent.service.support.chat.structured;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 置信度下界约束（阈值由 {@link MinConfidenceValidator} 从配置读取，故不带 value 成员）。
 *
 * <p>保留 {@code value()} 是为了表达"默认值"，但真正生效的是
 * {@code ai.chat.structured.min-confidence} —— 见校验器注释里对"为什么可配"的说明。
 */
@Documented
@Constraint(validatedBy = MinConfidenceValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface MinConfidence {

	String message() default "confidence 低于配置要求的下界";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	/** 仅作为文案/文档用途的默认值，实际阈值见配置项 */
	double value() default 0.0;
}

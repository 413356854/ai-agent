package ai.agent.service.chat.structured;

import ai.agent.service.support.chat.structured.MinConfidence;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 结构化输出的目标结构。
 *
 * <p><b>为什么用 record 而不是 DTO 类</b>：结构化输出的本质是"模型吐 JSON → 反序列化成对象"，
 * 一个不可变值对象就够了，不需要 setter / equals / toString 那套样板代码。
 * record 还能让 {@code BeanOutputConverter} 通过反射拿到字段顺序与类型来生成 JSON Schema
 * （{@code getJsonSchema()}），把 schema 写进提示词 —— 这是"让模型知道该输出什么"的关键一步。
 *
 * <p><b>校验注解不是装饰，它是这个功能的核心</b>。实测 DeepSeek **不支持**
 * {@code response_format=json_schema}（strict 模式），只能用 {@code json_object}，
 * 而 json_object 只保证"是合法 JSON"，不保证：
 * <ul>
 *   <li>字段齐全（模型可能漏掉 {@code confidence}）</li>
 *   <li>类型正确（{@code confidence} 可能给成字符串 "0.9"）</li>
 *   <li>取值合理（{@code confidence} 可能给 95 而不是 0.95）</li>
 * </ul>
 * 这三种情况 JSON 解析<b>都会成功</b>，只有校验能识破。识破之后把"哪个字段、错在哪"
 * 回灌给模型让它自己改 —— 这就是 Retry &amp; Reflection Loop。
 *
 * <p><b>为什么 confidence 用 {@code Double} 而不是 {@code double}</b>：JSON 里字段缺失时，
 * 基本类型会被反序列化成 0.0（一个看起来合法的值），校验器再也发现不了"模型根本没给"。
 * 用包装类型时缺失就是 {@code null}，配合 {@code @NotNull} 才能如实报错。
 * 这是"包装类型 vs 基本类型"在契约边界上的经典选择。
 */
public record StructuredAnswer(

		/** 回答正文。空字符串和 null 都算不合格 —— 模型偶尔会返回空对象来"交差"。 */
		@NotBlank(message = "answer 不能为空")
		@Size(max = 2000, message = "answer 不能超过 2000 字")
		String answer,

		/**
		 * 模型自评的置信度，0~1。
		 *
		 * <p>上限特意用 1.0（而不是 0.99 之类的宽松值）：实测模型很容易给 95、90 这种
		 * "百分制"数值，这正是校验要抓的典型错误 —— 抓到了回灌一次，模型基本都能改成 0.95。
		 *
		 * <p>下界用自定义的 {@link MinConfidence}（阈值来自配置）而不是写死的
		 * {@code @DecimalMin("0.0")}：默认 0.0 表示不设门槛，但"必须给出确定答案"的场景
		 * （例如字段抽取）可以把它调到 0.6 —— 模型说"我不确定"就会被要求重答。
		 */
		@NotNull(message = "confidence 不能缺失")
		@MinConfidence
		@DecimalMax(value = "1.0", message = "confidence 不能大于 1（注意：要用 0~1 的小数，不是百分制）")
		Double confidence,

		/** 关键词。要求非空，因为"给不出关键词"通常意味着回答本身是空泛的。 */
		@NotEmpty(message = "keywords 不能为空数组")
		@Size(max = 10, message = "keywords 最多 10 个")
		List<@NotBlank(message = "keywords 里不能有空字符串") String> keywords
) {

	/**
	 * 模型完全改不对时的兜底答案。
	 *
	 * <p>为什么不抛异常：对调用方（前端/业务系统）来说，"拿到一个明确标注降级的结果"
	 * 远好过"拿到 500"。降级信息本身也是有价值的数据 —— 上游可以据此决定要不要转人工。
	 * {@code degraded=true} 这个标记就是给上游看的开关。
	 */
	public static StructuredAnswer degraded(String reason) {
		return new StructuredAnswer(reason, 0.0, List.of("降级"));
	}
}

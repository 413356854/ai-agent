package ai.agent.entity.rag.kb;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 分块策略（{@code knowledge_base.chunk_strategy}）。
 *
 * <p>只提供两种，映射到本项目阶段二已有的两个 {@code DocumentChunker} 实现：
 * {@link #LENGTH} → {@code FixedSizeChunker}（按字符定长）、
 * {@link #STRUCTURAL} → {@code StructuralChunker}（按标题结构切，带标题路径面包屑）。
 *
 * <h2>admin 的另外两个选项为什么没有搬过来</h2>
 * admin 的界面上有 {@code length / page / title / regex} 四个选项，
 * 但它的 {@code KnowledgeBaseIndexPipeline#transform} 实际只有两条分支
 * （{@code REGEX} 走正则切分，其余全部走 {@code TokenTextSplitter}）——
 * 也就是 {@code page} 与 {@code title} 选了等于没选。
 *
 * <p>这里不复刻那个空档，理由是两者在本项目都缺前置条件：
 * <ul>
 *   <li>{@code page}：本项目的解析块（{@code ParsedBlock}）没有页码字段，
 *       按页切要先让解析层产出页码，是另一件事；</li>
 *   <li>{@code regex}：用户给的正则是一条没有上界的切分规则（匹配每个字符就是 N 块），
 *       而它跑在索引线程里。定长切分已经能表达"按长度切"。</li>
 * </ul>
 * 两者将来要加的话，接缝是"一个 {@code DocumentChunker} 实现 + 这里加一个枚举值"。
 */
public enum KbChunkStrategy {

	/** 按字符定长切（字符数，不是 token 数）。 */
	LENGTH("length", "定长切分"),

	/** 按标题结构切（带标题路径）。 */
	STRUCTURAL("structural", "按标题结构切分");

	private final String value;

	private final String label;

	KbChunkStrategy(String value, String label) {
		this.value = value;
		this.label = label;
	}

	/** 配置/接口上写的取值。 */
	public String value() {
		return value;
	}

	/** 界面上显示的字。 */
	public String label() {
		return label;
	}

	/**
	 * 解析。
	 *
	 * <p>认不出来<b>抛异常</b>而不是落默认值：这里的所有调用方都是"有一个请求或一行数据可以被告知"的场景
	 * （接口入参、库里的取值）。静默降级会让"选了按标题切、结果按长度切了"变成一件看不出来的事。
	 */
	public static KbChunkStrategy parse(String raw) {
		if (raw != null && !raw.isBlank()) {
			for (KbChunkStrategy strategy : values()) {
				if (strategy.value.equalsIgnoreCase(raw.strip()) || strategy.name().equalsIgnoreCase(raw.strip())) {
					return strategy;
				}
			}
		}
		throw new IllegalArgumentException("不认识的分块策略：「%s」。可用取值：%s".formatted(raw, available()));
	}

	/** 报错文案里列的可用取值（与 {@link #parse} 同一处维护）。 */
	public static String available() {
		return Arrays.stream(values()).map(strategy -> strategy.value + "=" + strategy.label)
				.collect(Collectors.joining("、"));
	}

}

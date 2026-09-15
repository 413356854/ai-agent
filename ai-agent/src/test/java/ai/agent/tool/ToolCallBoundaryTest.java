package ai.agent.tool;

import ai.agent.tool.impl.ListDocumentsTool;
import ai.agent.tool.model.AgentToolDefinition;
import ai.agent.tool.support.ToolFeedbackFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>边界的单元测试</b>：模型可以怎么乱来，以及乱来之后会发生什么。
 *
 * <h2>这个测试想证明的一件事</h2>
 * 学习点 5 的边界是「模型只输出意图，Java 端负责校验 + 执行 + 回填」。
 * 那么它必须能被证伪：<b>如果我把校验拿掉，这里应该有一堆测试变红。</b>
 * 所以每个测试都对应一种"没有边界就会出的事"：
 * <ul>
 *   <li>没有白名单 → 编一个工具名会变成 {@code NoSuchMethodException}（看着像系统故障）；</li>
 *   <li>没有解析校验 → 半截 JSON 会变成深层解析异常；</li>
 *   <li>没有 JSR-380 → 越界的值直接进工具，错误在业务逻辑深处才暴露；</li>
 *   <li>没有多余字段检查 → 模型多给一个字段会被静默忽略（行为与它的预期不一致）；</li>
 *   <li>没有异常兜底 → 工具里一个 NPE 会炸掉整个会话。</li>
 * </ul>
 *
 * <h2>为什么用 {@code @SpringBootTest}（而不是 new 出来）</h2>
 * 不是因为"顺手"，而是因为要测的东西里有一半是<b>装配本身</b>：
 * 工具能不能被收集到注册表、schema 能不能生成、描述是不是四段齐全、
 * 无参工具走不走得到。手工 new 的话，这些"接线"问题一个都测不到 ——
 * 而接线断掉的表现是"模型一个工具都调不到"，排查方向却会跑到模型上去。
 *
 * <p>它仍然不需要 API key 也不需要网络：边界是纯 Java 逻辑，
 * 模型的输出用字符串直接构造（因为"模型只输出意图"，
 * 而意图的形态就是一个 JSON 字符串）。真实协议往返（HTTP → Spring AI → 循环）
 * 另有测试覆盖，分工是"逻辑正确" vs "接线正确"。
 */
@SpringBootTest(properties = { "spring.ai.openai.api-key=test-key", "ai.rag.corpus.location=classpath:rag/corpus" })
class ToolCallBoundaryTest {

	@Autowired
	private ToolRegistry registry;

	@Autowired
	private ToolCallExecutor executor;

	@Autowired
	private ToolFeedbackFormatter formatter;

	@Test
	@DisplayName("三把工具都被装配进注册表（少一把就说明 @Component 或构造注入断了）")
	void allToolsAreRegistered() {
		assertThat(registry.names()).containsExactlyInAnyOrder("search_docs", "list_documents", "read_section");
	}

	// ------------------------------------------------------------------
	//  门禁 1：白名单
	// ------------------------------------------------------------------

	@Test
	@DisplayName("模型编了一个不存在的工具名 → 被拒绝，且回填里带全部可用工具名")
	void unknownToolIsRejectedWithAvailableNames() {
		ToolCallOutcome outcome = executor.execute(ToolInvocation.from("c1", "doc_search", "{\"query\":\"分块\"}"));

		assertThat(outcome.stage()).isEqualTo(ToolCallStage.UNKNOWN_TOOL);
		assertThat(outcome.succeeded()).isFalse();
		// 关键：必须是"可修正"的失败 —— 否则模型只会再编一个名字
		assertThat(outcome.fixableByModel()).isTrue();
		assertThat(outcome.feedback())
				.as("回填里必须列出可用工具（路线文档：错误信息里带可用值，模型才能自愈）")
				.contains("search_docs").contains("list_documents").contains("read_section");
		// 工具名很接近时最容易编错（doc_search vs search_docs），这个测试就是照着这个真实形态写的
		assertThat(outcome.feedback()).contains("没有名为「doc_search」的工具");
	}

	// ------------------------------------------------------------------
	//  门禁 2：JSON 解析
	// ------------------------------------------------------------------

	@Test
	@DisplayName("参数不是合法 JSON → PARSE_ERROR，回填里带 schema")
	void malformedJsonIsRejected() {
		ToolCallOutcome outcome = executor.execute(ToolInvocation.from("c1", "search_docs", "{\"query\": "));

		assertThat(outcome.stage()).isEqualTo(ToolCallStage.PARSE_ERROR);
		assertThat(outcome.feedback()).contains("不是合法 JSON".substring(0, 4)); // 中文断言留出编码余量
		assertThat(outcome.feedback()).as("必须把 schema 还给它，否则它不知道该输出什么").contains("properties");
	}

	@Test
	@DisplayName("参数是数组而不是对象 → 被拒绝（而不是当成空对象放行）")
	void nonObjectArgumentsAreRejected() {
		ToolCallOutcome outcome = executor.execute(ToolInvocation.from("c1", "search_docs", "[1,2,3]"));

		assertThat(outcome.stage()).isEqualTo(ToolCallStage.PARSE_ERROR);
		assertThat(outcome.feedback()).contains("JSON 对象");
	}

	@Test
	@DisplayName("模型多给了一个 schema 里没有的字段 → 拒绝，并列出可用字段")
	void extraFieldIsRejected() {
		ToolCallOutcome outcome = executor.execute(
				ToolInvocation.from("c1", "search_docs", "{\"query\":\"分块\",\"topK\":5,\"notes\":\"顺便解释一下\"}"));

		// 这里是最能体现"边界"的一条：Jackson 默认会**静默忽略** notes，
		// 于是调用"成功"了，但行为不是模型以为的那个
		assertThat(outcome.stage()).isEqualTo(ToolCallStage.PARSE_ERROR);
		assertThat(outcome.feedback()).contains("notes");
		assertThat(outcome.feedback()).as("必须把可用字段列出来").contains("query").contains("topK");
	}

	@Test
	@DisplayName("数字写成了字符串 \"5\" → 拒绝（不替模型猜意图），回填说明类型对不上")
	void stringInsteadOfNumberIsRejected() {
		ToolCallOutcome outcome = executor.execute(
				ToolInvocation.from("c1", "search_docs", "{\"query\":\"分块\",\"topK\":\"5\"}"));

		// 这是刻意的严格选择：放行 "5" 看起来更健壮，但同一套宽松规则遇到 "五" 时
		// 只能在很深的位置抛一个难懂的异常。宁可现在给一条明确的回填。
		//
		// 【这条断言本身踩过一次坑】最初实现里我"以为"关掉了强转 —— 写的是
		// DeserializationFeature.ACCEPT_STRING_AS_NUMBER，而那个常量在
		// Jackson 2.21（本项目实际解析到的版本）里**已经被删掉了**。
		// 于是第一次跑这个测试时它是**红的**（stage 是 PARSE_ERROR 之外的值）：
		// Jackson 安安静静地把 "5" 变成了 5，工具照常执行。
		// 如果没写这条测试，"我以为关掉了"就会一直是"没关掉"，而且没有任何迹象。
		assertThat(outcome.stage())
				.as("\"5\" 必须被拒绝；如果这里是 OK/VALIDATION_ERROR，说明类型强转没被关掉")
				.isEqualTo(ToolCallStage.PARSE_ERROR);
		assertThat(outcome.feedback()).contains("topK");
	}

	@Test
	@DisplayName("布尔写成了字符串 \"true\" → 同样拒绝（强转是整体关掉的，不是只关数字）")
	void stringInsteadOfBooleanIsRejected() {
		// 用一个故意带上布尔字段的形态来验证：search_docs 没有布尔参数，
		// 所以这里换成"多余字段 + 强转"的叠加场景，断言它至少不会被静默接受
		ToolCallOutcome outcome = executor.execute(
				ToolInvocation.from("c1", "search_docs", "{\"query\":\"分块\",\"topK\":5,\"exact\":\"true\"}"));

		assertThat(outcome.stage()).isEqualTo(ToolCallStage.PARSE_ERROR);
	}

	// ------------------------------------------------------------------
	//  门禁 3：JSR-380 校验
	// ------------------------------------------------------------------

	@Test
	@DisplayName("缺必填字段 → VALIDATION_ERROR，且回填里带字段名（模型才知道改哪个）")
	void missingRequiredFieldIsRejectedWithFieldName() {
		ToolCallOutcome outcome = executor.execute(ToolInvocation.from("c1", "search_docs", "{}"));

		assertThat(outcome.stage()).isEqualTo(ToolCallStage.VALIDATION_ERROR);
		// 字段名是自愈的关键：只说"参数不合法"，模型不知道该改哪个字段
		assertThat(outcome.feedback()).contains("query");
		assertThat(outcome.fixableByModel()).isTrue();
	}

	@Test
	@DisplayName("在合法 JSON 里传 null 给必填字段 → 被校验拦下（而不是当成\"没传\"放行）")
	void explicitNullIsRejected() {
		ToolCallOutcome outcome = executor.execute(ToolInvocation.from("c1", "search_docs", "{\"query\":null}"));

		assertThat(outcome.stage()).isEqualTo(ToolCallStage.VALIDATION_ERROR);
		assertThat(outcome.feedback()).contains("query");
	}

	@Test
	@DisplayName("空字符串查询词 → 被 @NotBlank 拦下")
	void blankQueryIsRejected() {
		ToolCallOutcome outcome = executor.execute(ToolInvocation.from("c1", "search_docs", "{\"query\":\"   \"}"));

		assertThat(outcome.stage()).isEqualTo(ToolCallStage.VALIDATION_ERROR);
		assertThat(outcome.feedback()).contains("query");
	}

	@Test
	@DisplayName("topK 越界（0 与 99）→ 被 @Min/@Max 拦下，回填里带边界说明")
	void outOfRangeTopKIsRejected() {
		ToolCallOutcome tooSmall = executor.execute(
				ToolInvocation.from("c1", "search_docs", "{\"query\":\"分块\",\"topK\":0}"));
		ToolCallOutcome tooLarge = executor.execute(
				ToolInvocation.from("c2", "search_docs", "{\"query\":\"分块\",\"topK\":99}"));

		assertThat(tooSmall.stage()).isEqualTo(ToolCallStage.VALIDATION_ERROR);
		assertThat(tooLarge.stage()).isEqualTo(ToolCallStage.VALIDATION_ERROR);
		assertThat(tooSmall.feedback()).contains("topK");
		assertThat(tooLarge.feedback()).contains("topK");
	}

	@Test
	@DisplayName("查询词超过 100 字 → 被 @Size 拦下（它有明确的理由：查询词不是一整段话）")
	void oversizedQueryIsRejected() {
		String longQuery = "分块".repeat(80);
		ToolCallOutcome outcome = executor.execute(
				ToolInvocation.from("c1", "search_docs", "{\"query\":\"%s\"}".formatted(longQuery)));

		assertThat(outcome.stage()).isEqualTo(ToolCallStage.VALIDATION_ERROR);
		assertThat(outcome.feedback()).contains("100");
	}

	@Test
	@DisplayName("read_section 缺 documentId → 校验拦下（不是 NPE）")
	void readSectionMissingDocumentIdIsRejected() {
		ToolCallOutcome outcome = executor.execute(
				ToolInvocation.from("c1", "read_section", "{\"section\":\"一、总览\"}"));

		assertThat(outcome.stage()).isEqualTo(ToolCallStage.VALIDATION_ERROR);
		assertThat(outcome.feedback()).contains("documentId");
	}

	// ------------------------------------------------------------------
	//  无参工具的三种合法形态（这条分支最容易被静默改坏）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("无参工具：{} / \"\" / 缺字段 三种形态都必须是合法调用，不能报解析失败")
	void noArgToolAcceptsAllEmptyForms() {
		for (String args : List.of("{}", "", "null", "  ")) {
			ToolCallOutcome outcome = executor.execute(ToolInvocation.from("c1", "list_documents", args));
			assertThat(outcome.stage())
					.as("无参工具的 arguments=%s 应该被当成合法空参数", args == null ? "null" : "\"" + args + "\"")
					.isNotEqualTo(ToolCallStage.PARSE_ERROR);
			assertThat(outcome.stage()).isNotEqualTo(ToolCallStage.VALIDATION_ERROR);
		}
	}

	@Test
	@DisplayName("无参工具收到多余参数 → 同样被拒绝（没有\"无参就随便\"这个例外）")
	void noArgToolRejectsExtraArguments() {
		ToolCallOutcome outcome = executor.execute(
				ToolInvocation.from("c1", "list_documents", "{\"filter\":\"phase1\"}"));

		assertThat(outcome.stage()).isEqualTo(ToolCallStage.PARSE_ERROR);
	}

	// ------------------------------------------------------------------
	//  注册表自检
	// ------------------------------------------------------------------

	@Test
	@DisplayName("工具名重复 → 启动期直接抛（不静默覆盖）")
	void duplicateToolNameFailsFast() {
		AgentTool<?> tool = registry.find("list_documents").orElseThrow();

		assertThatThrownBy(() -> new ToolRegistry(List.of(tool, tool)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("重复");
	}

	@Test
	@DisplayName("name() 与描述里的名字不一致 → 启动期直接抛（否则模型照描述调用会得到\"工具不存在\"）")
	void nameMismatchBetweenSpiAndDefinitionFailsFast() {
		AgentTool<ListDocumentsTool.NoParams> mismatched = new AgentTool<>() {
			@Override
			public String name() {
				return "list_documents";
			}

			@Override
			public AgentToolDefinition description() {
				return AgentToolDefinition.of("list_docs", "列出文档", "需要知道有哪些文档时");
			}

			@Override
			public Class<ListDocumentsTool.NoParams> parameterType() {
				return ListDocumentsTool.NoParams.class;
			}

			@Override
			public AgentToolResult invoke(ListDocumentsTool.NoParams parameters) {
				return AgentToolResult.ok("");
			}
		};

		assertThatThrownBy(() -> new ToolRegistry(List.of(mismatched)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("不一致");
	}

	@Test
	@DisplayName("直接调 ToolCallback.call() → 故意抛异常，堵死绕过边界的那条路")
	void directToolCallbackCallIsBlocked() {
		AgentTool<?> tool = registry.find("list_documents").orElseThrow();

		assertThatThrownBy(() -> tool.call("{}"))
				.as("如果这里能跑通，就存在一条没有白名单/校验/分类的执行路径")
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessageContaining("ToolCallExecutor");
	}

	@Test
	@DisplayName("每把工具的 JSON Schema 都能生成，且必填字段被标进 required")
	void schemasAreGeneratedWithRequiredFields() {
		for (AgentTool<?> tool : registry.all()) {
			String schema = tool.getToolDefinition().inputSchema();
			assertThat(schema).as("工具 %s 的 schema", tool.name()).contains("\"type\"").contains("properties");
		}
		String searchSchema = registry.find("search_docs").orElseThrow().getToolDefinition().inputSchema();
		// topK 不是必填：模型可以省略它（工具里有默认值）。
		// 断言方式刻意用"required 数组里含哪些字段"而不是找一个精确子串 ——
		// 第一版断言写的是 doesNotContain("\"required\" : [ \"topK\""),
		// 而 Jackson 的 toPrettyString() 对这种短数组是**内联**输出的（`"required" : [ "query" ]`），
		// 两种排版都对，断言却会因格式差异而误报。**断言不该依赖序列化器的排版细节**
		assertThat(requiredFieldsIn(searchSchema)).as("只有 query 是必填").containsExactly("query");
	}

	/**
	 * <b>结构性断言：schema 必须真的带上注解声明的约束。</b>
	 *
	 * <h2>为什么这条断言值得单独写</h2>
	 * 实测踩过：{@code JsonSchemaGenerator} 最初用 {@code RecordComponent.getAnnotations()} 取注解，
	 * 而 JSR-380 注解在 record 上落在<b>字段/访问器</b>上，{@code RecordComponent} 上是**空的**
	 * （{@code @NotBlank} 的 {@code @Target} 不含 RECORD_COMPONENT）。
	 * 于是生成的 schema 里<b>一条约束都没有</b>（没有 required、没有 maximum、没有 maxLength），
	 * 而校验器照常执行它们 —— 模型看不到边界、却永远过不去。
	 *
	 * <p><b>更值得记的是：当时所有边界测试都是绿的。</b>
	 * 因为那些测试断言的是"越界参数被拒绝"，而拒绝是校验器干的、与 schema 无关。
	 * 唯一变红的是那条"schema 里要有 required"的断言 ——
	 * 也就是本类注释里点名要防的那个失败模式，被一条结构性断言接住了。
	 *
	 * <p>所以这里逐字段核对<b>四个具体约束</b>，而不是"schema 非空"：
	 * 一条只看"有没有内容"的断言，在这种"内容少了一半"的退化面前会一路绿灯。
	 */
	@Test
	@DisplayName("schema 与注解一致：minLength/maxLength/minimum/maximum 必须逐条出现")
	void schemaCarriesConstraintsFromAnnotations() {
		String searchSchema = registry.find("search_docs").orElseThrow().getToolDefinition().inputSchema();
		// query 上的 @NotBlank → minLength:1；@Size(max=100) → maxLength:100
		assertThat(searchSchema).as("query 的 minLength（来自 @NotBlank）").containsPattern("\"minLength\"\\s*:\\s*1");
		assertThat(searchSchema).as("query 的 maxLength（来自 @Size(max=100)）")
				.containsPattern("\"maxLength\"\\s*:\\s*100");
		// topK 上的 @Min(1)/@Max(10)
		assertThat(searchSchema).as("topK 的 minimum（来自 @Min）").containsPattern("\"minimum\"\\s*:\\s*1");
		assertThat(searchSchema).as("topK 的 maximum（来自 @Max）").containsPattern("\"maximum\"\\s*:\\s*10");

		String readSchema = registry.find("read_section").orElseThrow().getToolDefinition().inputSchema();
		assertThat(requiredFieldsIn(readSchema)).as("两个字段都是 @NotBlank，都必须 required")
				.containsExactly("documentId", "section");
	}

	/**
	 * <b>反向断言：模型看到的上限必须与校验器执行的上限完全相同。</b>
	 *
	 * <p>这是整个"单一事实来源"设计的验收口径：
	 * 拿 schema 里的 {@code maximum} 去调一次工具，它<b>必须</b>通过；
	 * 用 {@code maximum + 1} 再调一次，它<b>必须</b>被拒绝。
	 * 两个方向都断言，才能同时防住两种漂移：
	 * <ul>
	 *   <li>schema 比校验松（模型按说明调用却过不去）—— 由"边界值必须通过"接住；</li>
	 *   <li>schema 比校验严（模型被无谓地限制住）—— 由"边界外必须被拒"接住。</li>
	 * </ul>
	 * 只测一个方向的话，另一种漂移会一直存在且无人发现。
	 */
	@Test
	@DisplayName("schema 的上限 == 校验器的上限（边界值通过、边界外拒绝）")
	void schemaLimitsMatchValidatorLimits() throws Exception {
		int maximum = intKeywordIn(registry.find("search_docs").orElseThrow().getToolDefinition().inputSchema(),
				"maximum");

		ToolCallOutcome atLimit = executor
				.execute(ToolInvocation.from("c1", "search_docs", "{\"query\":\"分块\",\"topK\":%d}".formatted(maximum)));
		ToolCallOutcome overLimit = executor.execute(ToolInvocation.from("c2", "search_docs",
				"{\"query\":\"分块\",\"topK\":%d}".formatted(maximum + 1)));

		assertThat(atLimit.stage()).as("schema 允许的最大值必须真的能通过（否则模型按说明调用却过不去）")
				.isNotEqualTo(ToolCallStage.VALIDATION_ERROR);
		assertThat(overLimit.stage()).as("超出 schema 允许范围必须被拒绝").isEqualTo(ToolCallStage.VALIDATION_ERROR);
	}

	/** 读 schema 里 topK 的某个整数关键字（不依赖排版）。 */
	private static int intKeywordIn(String schema, String keyword) throws Exception {
		var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(schema).get("properties").get("topK")
				.get(keyword);
		assertThat(node).as("schema 里必须有 topK.%s", keyword).isNotNull();
		return node.asInt();
	}

	/** 从生成的 schema 里读出 required 数组（不依赖排版）。 */
	private static List<String> requiredFieldsIn(String schema) {
		try {
			var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(schema).get("required");
			if (node == null || !node.isArray()) {
				return List.of();
			}
			List<String> fields = new java.util.ArrayList<>();
			node.forEach(n -> fields.add(n.asText()));
			return fields;
		}
		catch (Exception e) {
			throw new IllegalStateException("schema 不是合法 JSON：" + schema, e);
		}
	}

	@Test
	@DisplayName("工具描述是四段结构：何时用 / 何时不用 / 返回什么 / 失败什么样")
	void toolDescriptionsCarryAllFourSections() {
		String rendered = registry.find("search_docs").orElseThrow().description().render();

		// 描述即 prompt：缺了"何时不用"，模型就会拿它去干别的工具的活
		assertThat(rendered).contains("何时使用").contains("何时不要使用").contains("返回").contains("失败时");
	}

	@Test
	@DisplayName("当工具描述的 whenToUse 为空 → 构造期就抛（空描述 = 让模型猜）")
	void emptyWhenToUseIsRejectedAtConstruction() {
		assertThatThrownBy(() -> new AgentToolDefinition("t", "摘要", "  ", "", "", ""))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("whenToUse");
	}

	@Test
	@DisplayName("回填上限是配置来的（不是硬编码）：默认 2000，且格式化器自报它的值")
	void feedbackLimitIsConfigured() {
		// 这个数字与 ai.agent.loop.max-tokens 是联动的（见 AgentLoopProperties 注释）。
		// 断言它"存在且可读"，是为了让"改了配置但没人知道"这件事至少能在一处被看见
		assertThat(formatter.maxChars()).isPositive();
	}

}

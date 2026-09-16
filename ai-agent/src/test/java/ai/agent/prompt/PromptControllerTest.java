package ai.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prompt 端点的 HTTP 契约测试：<b>真的发一次请求</b>（阶段四第 3 件）。
 *
 * <h2>为什么必须真起端口，而不是只调 Controller 的方法</h2>
 * 这一条是本项目已经付过学费的规矩（第九轮 MCP 那次）：**"测试全绿 + 启动自检通过"
 * 不构成"端点能用"**。当时 8 项测试全绿、启动日志说注册了 3 把工具、自检也通过，
 * 而 {@code /mcp} 是 404 —— 因为三层各自只证明了链条的一段，
 * <b>没有任何一层证明"HTTP 路由注册了"</b>。
 * 所以对外契约的验证里必须有一层"真的发一次请求"。
 *
 * <h2>这个类真正要钉住的是<b>失败信号的种类</b></h2>
 * 三种失败必须是三个不同的状态码，因为它们的"该怎么办"完全不同：
 * <table border="1">
 *   <caption>为什么不能都回 500</caption>
 *   <tr><th>场景</th><th>码</th><th>使用者该做什么</th></tr>
 *   <tr><td>提示词名字拼错 / 版本不存在</td><td>404</td><td>改请求</td></tr>
 *   <tr><td>正文不合规（空、超长、围栏不配平、缺锚点、没写说明）</td><td>400</td>
 *       <td>改内容 —— 重试同一个内容永远不会成功</td></tr>
 *   <tr><td>要切换的前提已经过期（别人先动手了）</td><td>409</td>
 *       <td><b>刷新后重试是有意义的</b> —— 这正是它必须与 400 分开的原因</td></tr>
 * </table>
 * 全回 500 的后果在现实中很具体：使用者以为"系统坏了"，于是反复重试同一个请求。
 * 这与阶段一那条"上游 401 重试没有意义"是同一类判断。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.ai.openai.api-key=test-key", "spring.ai.openai.chat.options.model=fake-model" })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PromptControllerTest {

	/**
	 * 每次运行一个全新的文件库。
	 *
	 * <h2>⚠️ 为什么后缀是 nanoTime 而不是 pid（第一版就是 pid，踩到了）</h2>
	 * 第一版用 {@code ProcessHandle.current().pid()} 做文件名后缀，理由是"每次运行一个新库"。
	 * 而实测发现：<b>surefire 会复用 fork 出来的同一个 JVM</b>（本机连续几次都拿到 pid 12000），
	 * 于是那个文件库**跨 mvn 运行存活下来** —— 第二次跑时库里已经有一堆版本，
	 * 「存一版 → 新版本号 = 旧数量 + 1」这类断言就全错了，而错误信息看起来像
	 * "差异对比取错了起始版本"（实测报的是 `expected: 1 but was: 3`）。
	 *
	 * <p>教训不是"换个后缀"，而是：<b>一个用于隔离的标识，必须真的每次运行都不同</b>。
	 * pid 在"进程不复用"的假设下是对的，而那个假设在测试框架里不成立。
	 * 换成一个进程内单调递增的量（{@code System.nanoTime()}），
	 * 与"同一个测试类里所有方法共用同一个库"这个要求也不冲突 ——
	 * {@code @DynamicPropertySource} 每个上下文只解析一次。
	 */
	@DynamicPropertySource
	static void freshDb(DynamicPropertyRegistry registry) throws IOException {
		Path dbDir = Path.of("target", "prompt-controller");
		Files.createDirectories(dbDir);
		registry.add("ai.agent.store.url",
				() -> "jdbc:h2:file:" + dbDir.toAbsolutePath().toString().replace('\\', '/')
						+ "/prompts-ctrl-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
	}

	@LocalServerPort
	private int port;

	@Autowired
	private PromptService prompts;

	private RestClient client() {
		return RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> get(String path) {
		return client().get().uri(path).retrieve().body(Map.class);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> post(String path, Object body) {
		return client().post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
				.body(Map.class);
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	@Test
	@Order(1)
	@DisplayName("GET /api/prompts：返回两份提示词 + 各自当前生效的版本 + 热更新的边界说明")
	void listReturnsBothPrompts() {
		Map<String, Object> body = get("/api/prompts");

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> list = (List<Map<String, Object>>) body.get("prompts");
		assertThat(list).as("受管理的提示词是闭集，两份都要在").hasSize(PromptKind.values().length);
		assertThat(list).extracting(row -> row.get("kind"))
				.containsExactlyInAnyOrder("system-agent", "system-agent-tools");

		Map<String, Object> agentLoop = list.stream().filter(r -> "system-agent-tools".equals(r.get("kind")))
				.findFirst().orElseThrow();
		assertThat(agentLoop.get("activeVersion")).as("出厂版本已经在启动时入库并生效").isEqualTo(1);
		assertThat(agentLoop.get("activeHash").toString()).as("指纹是 12 位十六进制").hasSize(12);
		assertThat((Integer) agentLoop.get("activeTokens")).isPositive();
		assertThat(agentLoop.get("source")).as("来源是库，不是 classpath").isEqualTo("library");
		// "谁在读这一份"必须能直接看到 —— 否则改了哪一份会影响哪些功能要靠翻代码
		assertThat(agentLoop.get("readBy").toString()).contains("Agent");

		// 热更新的边界写在返回体里，而不是只写在文档里
		@SuppressWarnings("unchecked")
		Map<String, Object> hot = (Map<String, Object>) body.get("hotReload");
		assertThat(hot.get("takesEffect").toString()).as("说清什么时候生效").contains("下一次");
		assertThat(hot.get("doesNotAffect").toString())
				.as("★ 更要紧的是说清**什么不会**变：正在跑的那次运行与已经算出去的预算")
				.contains("已经在跑");
		assertThat(body.get("notes").toString()).as("这一页的纪律也要随数据一起交付").contains("只追加");
	}

	@Test
	@Order(2)
	@DisplayName("GET /{kind}/versions 与 /{kind}/versions/{n}：列表降序、详情带正文")
	void versionListAndDetail() {
		Map<String, Object> list = get("/api/prompts/system-agent-tools/versions");
		assertThat(list.get("count")).isEqualTo(1);

		Map<String, Object> detail = get("/api/prompts/system-agent-tools/versions/1");
		@SuppressWarnings("unchecked")
		Map<String, Object> version = (Map<String, Object>) detail.get("version");
		assertThat(version.get("content").toString()).as("详情必须带正文（编辑器要用它）").contains("工作方式");
		assertThat(version.get("active")).as("第 1 版当前是生效的").isEqualTo(true);
		assertThat(version.get("lines")).as("行数也一并给出，免得前端自己数").isNotNull();
	}

	@Test
	@Order(3)
	@DisplayName("GET /{kind}/diff：from 省略时默认对比『上一版 → 最新版』")
	void diffDefaultsToPreviousVersion() {
		PromptVersion v1 = prompts.activeVersion(PromptKind.AGENT_LOOP).orElseThrow();
		String appended = "\n\n新增的一行。\n";
		PromptChange saved = prompts.save(PromptKind.AGENT_LOOP, new PromptValidator.Input(PromptKind.AGENT_LOOP,
				v1.content() + appended, "加了一行，供diff用例使用", "test", false));

		Map<String, Object> diff = get("/api/prompts/system-agent-tools/diff");

		// 【这几条刻意不写死数字，而是与"实际内容"比】
		// 第一版写的是 `added == 1`（"我追加了一行，所以新增一行"），而它是**错的**：
		// 原来那份正文末尾没有换行符，于是追加 "\n\n新行\n" 实际产生的是 3 个新行
		// （空行 + 新行 + 空行）。断言本身与功能无关，红的是我的假设 ——
		// 这类"用例自身假设不成立"的失败最消耗时间，因为它看起来像功能错了
		int expectedAdded = (int) appended.chars().filter(c -> c == '\n').count();
		assertThat(diff.get("from")).as("没给 from 时自动取上一版（存量模式：当前生效那一版）")
				.isEqualTo(v1.version());
		assertThat(diff.get("to")).as("没给 to 时取最新版").isEqualTo(saved.saved().version());
		assertThat(diff.get("added")).as("新增行数 = 追加内容里的换行数（%d）".formatted(expectedAdded))
				.isEqualTo(expectedAdded);
		assertThat(diff.get("removed")).as("只是追加，没有删任何东西").isEqualTo(0);
		assertThat(diff.get("identical")).isEqualTo(false);
		assertThat(diff.get("note").toString()).as("差异算法本身也要解释一句（它决定了改动会不会被放大）")
				.contains("最长公共子序列");
		// 后端把估算值也带回来，页面据此显示"token +N"——
		// 而那正是"换提示词会让预算变紧"这件事在界面上的唯一入口
		assertThat(diff.get("tokenDelta")).isNotNull();
	}

	// ------------------------------------------------------------------
	//  失败信号：三种码各一条
	// ------------------------------------------------------------------

	@Test
	@Order(4)
	@DisplayName("名字不认识的提示词 → 404（不是 400，也不是静默当成默认那一份）")
	void unknownKindIs404() {
		assertThatThrownBy(() -> get("/api/prompts/not-a-prompt/versions"))
				.isInstanceOf(HttpClientErrorException.class)
				.satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
						.isEqualTo(HttpStatus.NOT_FOUND));
	}

	@Test
	@Order(5)
	@DisplayName("版本不存在 → 404")
	void unknownVersionIs404() {
		assertThatThrownBy(() -> get("/api/prompts/system-agent-tools/versions/999"))
				.isInstanceOf(HttpClientErrorException.class)
				.satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
						.isEqualTo(HttpStatus.NOT_FOUND));
	}

	@Test
	@Order(6)
	@DisplayName("★ 正文不合规 → 400，而且响应体里带着**每一条判据与具体数字**")
	void rejectedContentIs400WithReasons() {
		// 围栏不配平（粘贴被截断的最典型形态）
		assertThatThrownBy(() -> post("/api/prompts/system-agent-tools/versions",
				Map.of("content", "## 工作方式\n\n用工具查。\n## 不编造\n查不到就说查不到。\n```json\n{}\n",
						"note", "补一个示例")))
				.isInstanceOf(HttpClientErrorException.class)
				.satisfies(e -> {
					HttpClientErrorException bad = (HttpClientErrorException) e;
					assertThat(bad.getStatusCode())
							.as("400 而不是 500：内容是调用方能改的东西。回 500 会让人以为系统坏了然后反复重试")
							.isEqualTo(HttpStatus.BAD_REQUEST);
					String body = bad.getResponseBodyAsString();
					assertThat(body).as("错误码要能被程序识别").contains("rejected");
					assertThat(body).as("★ 必须指出是哪一条判据没过").contains("代码围栏配平");
					assertThat(body).as("★ 而且要给具体数字，否则使用者只能猜差多少").contains("奇数");
				});
	}

	@Test
	@Order(7)
	@DisplayName("★ 切换的前提过期 → 409，且响应体带『你以为的』与『实际的』两个版本号")
	void staleActivateIs409() {
		// 先把最新那一版切成生效（拿到一个已知的当前版本）
		Map<String, Object> versions = get("/api/prompts/system-agent-tools/versions");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) versions.get("versions");
		int latest = (Integer) rows.get(0).get("version");
		post("/api/prompts/system-agent-tools/activate", Map.of("version", latest, "expectedVersion", 1));

		// 现在当前是 latest，而我"以为"它是 1
		assertThatThrownBy(() -> post("/api/prompts/system-agent-tools/activate",
				Map.of("version", latest, "expectedVersion", 1)))
				.isInstanceOf(HttpClientErrorException.class)
				.satisfies(e -> {
					HttpClientErrorException conflict = (HttpClientErrorException) e;
					assertThat(conflict.getStatusCode())
							.as("409 而不是 400：**刷新之后重试是有意义的** —— 这正是它必须分开的原因")
							.isEqualTo(HttpStatus.CONFLICT);
					String body = conflict.getResponseBodyAsString();
					assertThat(body).contains("conflict");
					assertThat(body).as("★ 两个版本号都要给：只说『冲突了』会让人反复点同一个按钮")
							.contains("\"expectedVersion\"").contains("\"actualVersion\"");
				});
	}

	// ------------------------------------------------------------------
	//  写：存 → 激活 → 回滚 的完整往返
	// ------------------------------------------------------------------

	@Test
	@Order(8)
	@DisplayName("★ 完整往返：存（未生效）→ 激活 → 回滚（回滚返回的是**新版本号**）")
	void saveActivateRollbackRoundTrip() {
		Map<String, Object> versionsBefore = get("/api/prompts/system-agent-tools/versions");
		int countBefore = (Integer) versionsBefore.get("count");
		int activeBefore = (Integer) ((Map<?, ?>) versionsBefore.get("prompt")).get("activeVersion");

		// ① 存：必须 201，而且 body 里明确写着"尚未生效"
		Map<String, Object> created = post("/api/prompts/system-agent-tools/versions", Map.of(
				"content", "## 工作方式\n\n用工具查，别凭印象答。\n## 不编造\n查不到就说查不到，并说明查了什么。\n"
						+ "补".repeat(120),
				"note", "HTTP 往返用例写入的一版", "author", "controller-test"));
		@SuppressWarnings("unchecked")
		Map<String, Object> saved = (Map<String, Object>) created.get("version");
		int newVersion = (Integer) saved.get("version");
		assertThat(newVersion).isEqualTo(countBefore + 1);
		assertThat(saved.get("active")).as("存与生效是两次决定：刚存的这一版不该是生效的").isEqualTo(false);
		assertThat(created.get("note").toString()).as("界面要显示的那句话也来自后端，不是前端自己编的")
				.contains("尚未生效");
		assertThat(created.get("validation")).as("校验结论随写入结果一起返回").isNotNull();

		// ② 激活
		Map<String, Object> activated = post("/api/prompts/system-agent-tools/activate",
				Map.of("version", newVersion, "expectedVersion", activeBefore, "author", "controller-test"));
		assertThat(activated.get("activeVersion")).isEqualTo(newVersion);
		assertThat(activated.get("message").toString()).contains("切换生效");

		// ③ 回滚到最初那一版
		Map<String, Object> rolled = post("/api/prompts/system-agent-tools/rollback",
				Map.of("version", 1, "author", "controller-test", "note", "线上答案变差，回滚"));
		assertThat(rolled.get("noOp")).as("内容不同，所以这次回滚**确实产生了新版本**").isEqualTo(false);
		assertThat((Integer) rolled.get("createdVersion"))
				.as("★ 关键：回滚返回的是**新版本号**，不是被回滚到的那个 —— "
						+ "它如果等于 targetVersion，就说明实现变成了『把指针挪回去』，历史里那次回滚也就没了")
				.isGreaterThan(newVersion);
		assertThat(rolled.get("targetVersion")).isEqualTo(1);
		assertThat(rolled.get("activeVersion")).isEqualTo(rolled.get("createdVersion"));

		// ④ 再回滚一次到同一版 → 内容已相同，不该再新增
		Map<String, Object> again = post("/api/prompts/system-agent-tools/rollback",
				Map.of("version", 1, "author", "controller-test", "note", "再点一次"));
		assertThat(again.get("noOp")).as("★ 内容已经相同 → noOp，而不是往历史里塞一条解释不了的记录")
				.isEqualTo(true);
		assertThat(again.get("createdVersion")).as("没有新增版本").isNull();
		assertThat(again.get("note").toString()).as("而且必须**明说**没产生副作用（否则读者会以为回滚失败了）")
				.contains("没有新增版本");

		Map<String, Object> after = get("/api/prompts/system-agent-tools/versions");
		assertThat(after.get("count")).as("总共只多了 2 版：一次存 + 一次回滚").isEqualTo(countBefore + 2);
	}

}

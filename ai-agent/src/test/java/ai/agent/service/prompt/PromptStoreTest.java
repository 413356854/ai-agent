package ai.agent.service.prompt;

import ai.agent.entity.prompt.PromptChange;
import ai.agent.entity.prompt.PromptVersion;
import ai.agent.service.impl.agent.store.JdbcAgentRunStore;
import ai.agent.config.AgentStoreProperties;
import ai.agent.config.ChatCallProperties;
import ai.agent.service.token.TokenEstimator;
import ai.agent.service.impl.token.CharacterClassTokenEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提示词版本存储 + 服务的行为测试（阶段四第 3 件）。
 *
 * <h2>为什么用真库（H2 文件）而不是假存储</h2>
 * 这一层要证明的东西里有三条<b>只有真库能证</b>：
 * <ol>
 *   <li>"版本号 = 当前最大 + 1"在<b>并发保存</b>下会不会撞号 —— 假存储里加一把锁就"通过"了，
 *       而真库上还有主键冲突这条路径；</li>
 *   <li>"切换是一次带前置条件的 UPDATE"是不是真的原子 —— 这一条完全落在 SQL 上；</li>
 *   <li>DDL 在 H2 的 {@code MODE=MySQL} 下建不建得起来（{@code kind} 不是保留字、
 *       {@code TEXT} 能用、复合主键能用）。</li>
 * </ol>
 * 用 {@code @TempDir} 而不是 {@code jdbc:h2:mem:}：文件库让"真的写下去了"成为事实，
 * 而内存库在"以为写了其实没写"的实现上也可能碰巧通过。
 *
 * <p><b>顺带一个刻意的设计验证</b>：{@code JdbcPromptStore} 通过
 * {@code JdbcAgentRunStore.pool()} 借连接池，所以这里的装配顺序就是生产上的那个顺序 ——
 * 如果将来有人给它改成自建池，这个测试仍然会通过，但成本看板那一轮记的
 * "池数随组件数线性增长"就会重新出现。所以那条纪律写在类的注释里，而不是靠测试。
 */
class PromptStoreTest {

	@TempDir
	Path tempDir;

	private JdbcPromptStore store;

	@BeforeEach
	void setUp() {
		// AgentStoreProperties 是 record（没有 setter）—— 这本身是一个好的设计：
		// 连接参数不该在运行期被某处顺手改掉
		AgentStoreProperties props = new AgentStoreProperties(
				"jdbc:h2:file:" + tempDir.toAbsolutePath().toString().replace('\\', '/')
						+ "/prompt-store;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
				"sa", "", "prompt-store-test", false);
		store = new JdbcPromptStore(new JdbcAgentRunStore(props));
	}

	// ------------------------------------------------------------------
	//  基本读写
	// ------------------------------------------------------------------

	@Test
	@DisplayName("空库：没有版本、没有生效版本（不是抛异常，也不是编一个默认）")
	void emptyStoreIsEmpty() {
		assertThat(store.versionsOf(PromptKind.AGENT_LOOP)).isEmpty();
		assertThat(store.active(PromptKind.AGENT_LOOP)).as("『还没有版本』是一个合法的状态").isEmpty();
		assertThat(store.find(PromptKind.AGENT_LOOP, 1)).isEmpty();
	}

	@Test
	@DisplayName("版本号每份提示词各自从 1 开始，而且**各自**递增（不是全局递增）")
	void versionNumbersArePerKind() {
		PromptVersion a1 = store.save(PromptKind.AGENT_LOOP, "agent 第一版", "首次", "t", null, 10);
		PromptVersion b1 = store.save(PromptKind.CHAT_SYSTEM, "chat 第一版", "首次", "t", null, 10);
		PromptVersion a2 = store.save(PromptKind.AGENT_LOOP, "agent 第二版", "改了一次", "t", null, 10);

		assertThat(a1.version()).as("两份提示词的第一版都叫第 1 版").isEqualTo(1);
		assertThat(b1.version()).as("换成全局递增的话，这里会是 2 —— 而『第几版』对读者就没意义了")
				.isEqualTo(1);
		assertThat(a2.version()).isEqualTo(2);
	}

	@Test
	@DisplayName("列表按版本号**降序**（界面直接照着渲染，不需要自己排）")
	void versionsAreDescending() {
		store.save(PromptKind.AGENT_LOOP, "v1", "首次", "t", null, 10);
		store.save(PromptKind.AGENT_LOOP, "v2", "第二次", "t", null, 10);
		store.save(PromptKind.AGENT_LOOP, "v3", "第三次", "t", null, 10);

		assertThat(store.versionsOf(PromptKind.AGENT_LOOP)).extracting(PromptVersion::version)
				.containsExactly(3, 2, 1);
	}

	@Test
	@DisplayName("指纹与长度是写入时算好的，读回来一致（不是每次现算、也不是外部传进来的）")
	void hashAndCountsArePersisted() {
		String content = "正文第一行\n正文第二行";
		PromptVersion saved = store.save(PromptKind.AGENT_LOOP, content, "首次", "t", null, 42);

		PromptVersion read = store.find(PromptKind.AGENT_LOOP, saved.version()).orElseThrow();
		assertThat(read.hash()).isEqualTo(PromptVersion.hashOf(content)).hasSize(12);
		assertThat(read.charCount()).isEqualTo(content.length());
		assertThat(read.tokenEstimate()).as("token 估算由调用方给，原样存回").isEqualTo(42);
		assertThat(read.createdAt()).as("落库时间要真的写下去（界面上要显示「这一版是什么时候的」）").isNotNull();
	}

	@Test
	@DisplayName("作者留空存 NULL 而不是空串（『没署名』与『署名成了空』是两件事）")
	void blankAuthorBecomesNull() {
		PromptVersion saved = store.save(PromptKind.AGENT_LOOP, "正文", "首次", null, null, 10);

		assertThat(store.find(PromptKind.AGENT_LOOP, saved.version()).orElseThrow().author()).isNull();
	}

	@Test
	@DisplayName("forkedFrom 落库（回滚的凭据：这一版复制自哪一版）")
	void forkedFromIsPersisted() {
		store.save(PromptKind.AGENT_LOOP, "出厂正文", "出厂", "system", null, 10);
		PromptVersion copy = store.save(PromptKind.AGENT_LOOP, "出厂正文", "回滚到第 1 版", "oncall", 1, 10);

		assertThat(store.find(PromptKind.AGENT_LOOP, copy.version()).orElseThrow().forkedFrom()).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	//  切换（这一节的每一条都落在 SQL 的原子性上）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("切换生效：带正确的 expectedVersion 时成功，active 指向新版")
	void activateSwitchesPointer() {
		PromptVersion v1 = store.save(PromptKind.AGENT_LOOP, "v1", "首次", "t", null, 10);
		PromptVersion v2 = store.save(PromptKind.AGENT_LOOP, "v2", "第二次", "t", null, 10);
		store.activate(PromptKind.AGENT_LOOP, v1.version(), null);

		Optional<PromptVersion> switched = store.activate(PromptKind.AGENT_LOOP, v2.version(), v1.version());

		assertThat(switched).isPresent();
		assertThat(switched.get().version()).isEqualTo(v2.version());
		assertThat(store.active(PromptKind.AGENT_LOOP).orElseThrow().content()).isEqualTo("v2");
	}

	@Test
	@DisplayName("★ 前提过期 → 返回空，而且**生效版本一个都没动**（不是静默覆盖）")
	void activateWithStaleExpectationDoesNothing() {
		PromptVersion v1 = store.save(PromptKind.AGENT_LOOP, "v1", "首次", "t", null, 10);
		PromptVersion v2 = store.save(PromptKind.AGENT_LOOP, "v2", "第二次", "t", null, 10);
		store.activate(PromptKind.AGENT_LOOP, v2.version(), null);

		// 调用方以为当前是 v1（他打开页面时确实还是 v1）
		Optional<PromptVersion> result = store.activate(PromptKind.AGENT_LOOP, v1.version(), v1.version());

		assertThat(result).as("前提过期时必须返回空 —— 静默成功会把别人的决定悄悄抹掉").isEmpty();
		assertThat(store.active(PromptKind.AGENT_LOOP).orElseThrow().version())
				.as("被拒之后生效版本仍然是 v2")
				.isEqualTo(v2.version());
	}

	@Test
	@DisplayName("切换到一个不存在的版本 → 空（不报 SQL 错，也不悄悄建一版）")
	void activateUnknownVersionReturnsEmpty() {
		store.save(PromptKind.AGENT_LOOP, "v1", "首次", "t", null, 10);

		assertThat(store.activate(PromptKind.AGENT_LOOP, 99, null)).isEmpty();
		assertThat(store.active(PromptKind.AGENT_LOOP)).as("拒绝之后没有任何副作用").isEmpty();
		assertThat(store.versionsOf(PromptKind.AGENT_LOOP)).hasSize(1);
	}

	@Test
	@DisplayName("切换**不**新增版本（版本数是唯一的：切换是运维动作，不是新的一版内容）")
	void activateDoesNotCreateVersion() {
		PromptVersion v1 = store.save(PromptKind.AGENT_LOOP, "v1", "首次", "t", null, 10);
		PromptVersion v2 = store.save(PromptKind.AGENT_LOOP, "v2", "第二次", "t", null, 10);
		store.activate(PromptKind.AGENT_LOOP, v1.version(), null);

		store.activate(PromptKind.AGENT_LOOP, v2.version(), v1.version());
		store.activate(PromptKind.AGENT_LOOP, v1.version(), v2.version());

		assertThat(store.versionsOf(PromptKind.AGENT_LOOP)).as("来回切了两次，版本数不变").hasSize(2);
		assertThat(store.active(PromptKind.AGENT_LOOP).orElseThrow().version()).isEqualTo(v1.version());
	}

	// ------------------------------------------------------------------
	//  并发（这一节只有真库能验）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("★ 并发保存：版本号不撞号（『读最大 + 写』这两步在并发下必须被保护）")
	void concurrentSavesDoNotCollide() throws Exception {
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger failures = new AtomicInteger();
		try {
			for (int i = 0; i < threads; i++) {
				int index = i;
				pool.submit(() -> {
					try {
						start.await();
						store.save(PromptKind.AGENT_LOOP, "并发正文 " + index, "并发保存第 " + index + " 次", "t",
								null, 10);
					}
					catch (Exception e) {
						// 撞号时数据库会以主键冲突报错 —— 而这个异常正是"没保护"的证据，
						// 所以这里不能吞掉：它要变成测试失败
						failures.incrementAndGet();
					}
				});
			}
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(failures.get()).as("并发保存不该有任何一个失败（撞号会以主键冲突现形）").isZero();
		List<PromptVersion> versions = store.versionsOf(PromptKind.AGENT_LOOP);
		assertThat(versions).as("8 个并发保存 → 恰好 8 个版本").hasSize(threads);
		// 版本号必须恰好是 8..1 —— 出现重复说明"读最大 + 写"没被保护
		assertThat(versions).extracting(PromptVersion::version).containsExactly(8, 7, 6, 5, 4, 3, 2, 1);
	}

	// ------------------------------------------------------------------
	//  服务层：兜底链与"存 ≠ 生效"
	// ------------------------------------------------------------------

	@Test
	@DisplayName("★ 库里没有版本时，读内容走 classpath 兜底（保证 Agent 永远有一份提示词可用）")
	void fallsBackToClasspathWhenStoreIsEmpty() {
		TokenEstimator estimator = new CharacterClassTokenEstimator(new ChatCallProperties());
		PromptService service = new DefaultPromptService(store, estimator, new org.springframework.core.io.DefaultResourceLoader());

		String content = service.currentContent(PromptKind.AGENT_LOOP);

		assertThat(content).as("库空时不能返回空串 —— 空系统提示词会让模型用默认人格乱答").contains("工作方式");
		assertThat(service.currentFingerprint(PromptKind.AGENT_LOOP)).as("兜底时指纹前缀是 classpath：")
				.startsWith("classpath:");
		assertThat(service.activeVersion(PromptKind.AGENT_LOOP)).as("兜底不等于有版本").isEmpty();
		assertThat(service.tokenCount(PromptKind.AGENT_LOOP)).isPositive();
	}

	@Test
	@DisplayName("★ 存与生效是两次决定：保存之后 active 不动，激活之后才动")
	void saveDoesNotActivate() {
		TokenEstimator estimator = new CharacterClassTokenEstimator(new ChatCallProperties());
		PromptService service = new DefaultPromptService(store, estimator, new org.springframework.core.io.DefaultResourceLoader());

		PromptVersion base = store.save(PromptKind.AGENT_LOOP, "第一版正文（够长，免得被判过短）：工作方式、工具、不编造",
				"首次写入", "t", null, 20);
		store.activate(PromptKind.AGENT_LOOP, base.version(), null);

		PromptChange change = service.save(PromptKind.AGENT_LOOP, new PromptValidator.Input(PromptKind.AGENT_LOOP,
				base.content() + "，再补一句说明工具怎么用", "补一句工具说明", "t", false));

		assertThat(change.saved().version()).isEqualTo(2);
		assertThat(service.activeVersion(PromptKind.AGENT_LOOP).orElseThrow().version())
				.as("保存不自动生效：一次粘贴失误不该直接作用到线上")
				.isEqualTo(base.version());
		assertThat(service.currentContent(PromptKind.AGENT_LOOP)).as("而且读到的仍是旧版内容").isEqualTo(base.content());

		service.activate(PromptKind.AGENT_LOOP, change.saved().version(), base.version(), "t");
		assertThat(service.currentContent(PromptKind.AGENT_LOOP)).as("激活之后才换").isEqualTo(change.saved().content());
		assertThat(service.currentFingerprint(PromptKind.AGENT_LOOP)).isEqualTo("v2:" + change.saved().hash());
	}

	@Test
	@DisplayName("★ 回滚：新增一版 + forkedFrom 指向被回滚的那一版（不是把指针挪回去）")
	void rollbackAppendsWithForkedFrom() {
		TokenEstimator estimator = new CharacterClassTokenEstimator(new ChatCallProperties());
		PromptService service = new DefaultPromptService(store, estimator, new org.springframework.core.io.DefaultResourceLoader());

		PromptVersion v1 = store.save(PromptKind.AGENT_LOOP, "原始正文：工作方式、工具、不编造都要在", "首次写入", "t",
				null, 20);
		store.activate(PromptKind.AGENT_LOOP, v1.version(), null);
		PromptChange v2 = service.save(PromptKind.AGENT_LOOP, new PromptValidator.Input(PromptKind.AGENT_LOOP,
				"被改坏的正文：工作方式、工具、不编造都要在，但是后面加了一句有害的指令", "改坏了，用来演示回滚", "t",
				false));
		service.activate(PromptKind.AGENT_LOOP, v2.saved().version(), v1.version(), "t");

		PromptVersion rolled = service.rollback(PromptKind.AGENT_LOOP, v1.version(), "oncall", "线上答案变差，回滚");

		assertThat(rolled.version()).as("回滚产生的是第 3 版").isEqualTo(3);
		assertThat(rolled.content()).as("正文逐字节等于第 1 版").isEqualTo(v1.content());
		assertThat(rolled.forkedFrom()).as("forkedFrom 记着它是从第 1 版复制来的 —— 这就是『谁把哪一版拉回来』的凭据")
				.isEqualTo(1);
		assertThat(rolled.note()).as("回滚说明是调用方给的那句，不是旧版的说明").isEqualTo("线上答案变差，回滚");
		assertThat(store.versionsOf(PromptKind.AGENT_LOOP)).as("历史只追加：现在有 3 版").hasSize(3);
		assertThat(service.activeVersion(PromptKind.AGENT_LOOP).orElseThrow().version()).isEqualTo(3);
	}

	@Test
	@DisplayName("★ 回滚到一个和当前内容相同的版本 → 服务层仍会新增一版（去重是端点的决定，不是存储的）")
	void rollbackToIdenticalContentStillAppendsInService() {
		TokenEstimator estimator = new CharacterClassTokenEstimator(new ChatCallProperties());
		PromptService service = new DefaultPromptService(store, estimator, new org.springframework.core.io.DefaultResourceLoader());

		PromptVersion v1 = store.save(PromptKind.AGENT_LOOP, "正文：工作方式、工具、不编造", "首次写入", "t", null, 20);
		store.activate(PromptKind.AGENT_LOOP, v1.version(), null);

		PromptVersion rolled = service.rollback(PromptKind.AGENT_LOOP, v1.version(), "t", null);

		// 【为什么把"内容相同就不新增"放在端点而不是服务层】：
		// 服务层的职责是"把这一版变成生效版本"，它是一个纯粹的动作；
		// "要不要因为内容相同而跳过"是一条**面向使用者**的判断（避免历史里出现解释不了的记录），
		// 属于接口层。放错层的后果是：想"强制回滚一次"的人没有任何入口 ——
		// 因为服务层已经替他决定了"这次没意义"
		assertThat(rolled.version()).as("服务层照做：新增第 2 版").isEqualTo(2);
		assertThat(rolled.content()).isEqualTo(v1.content());
		assertThat(rolled.forkedFrom()).isEqualTo(1);
		assertThat(rolled.note()).as("没给说明时由系统补写一句，而不是留空").contains("回滚到第 1 版");
	}

}

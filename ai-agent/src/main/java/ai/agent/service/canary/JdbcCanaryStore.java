package ai.agent.service.canary;

import ai.agent.entity.canary.CanaryDecision;
import ai.agent.mapper.CanaryMapper;
import ai.agent.mapper.MapperRows;
import ai.agent.service.prompt.PromptKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link CanaryStore} 的存储实现 —— <b>SQL 在 {@link CanaryMapper} 里，本类只管口径</b>。
 *
 * <h2>★ 观测查询的两个口径，写错任何一个都会得到一个"看起来正常"的比例</h2>
 * <ol>
 *   <li><b>按运行去重，不按步骤</b>：{@code COUNT(DISTINCT run_id)}。
 *       数步骤的话，一次跑了 5 步的运行会被算 5 次 —— 于是"跑得久的那一组"
 *       在比例里被加权，而"新版是不是更啰嗦"恰恰是这次实验想看的东西之一
 *       （一个自证式的偏差）；</li>
 *   <li><b>参与分流的判据是"变体标签非空"，不是"版本号等于候选版"</b>。
 *       后者会把灰度<b>之前</b>就跑过那一版的运行也算进来 ——
 *       而那些运行压根没参与这次实验。</li>
 * </ol>
 * 两条口径的落点：去重在 XML 里（它是 SQL 的表达能力），
 * "哪些标签算参与"在本类的 {@link #observe} 里（它是业务判断）。
 *
 * <p>⚠️ 版本号是从 {@code prompt_fingerprint} 的 {@code v<数字>:} 前缀里解析的。
 * 解析不出来时记 0（那一行仍然出现在报告里，标签写"（无法归属版本）"）——
 * <b>而不是丢掉它</b>：丢掉会让"总运行数"与"逐版本之和"对不上，
 * 而两个数字对不上时，读的人只会信那个大的。
 *
 * <h2>建表与连接池：本类不再管了</h2>
 * 表在 {@code db/alert-schema.sql} 里（它与告警是同一件（第 5 件）的两半，
 * 共用同一张"事实"：告警的 canary-* 判据读的正是这里的观测分布），
 * 由 {@code spring.sql.init} 统一建；连接来自全应用唯一的 {@code DataSource}。
 * 原先那个"构造器里塞一个用不到的 JdbcAgentRunStore 来保证建表先发生"的参数已删除。
 */
@Component
public class JdbcCanaryStore implements CanaryStore {

	private static final Logger log = LoggerFactory.getLogger(JdbcCanaryStore.class);

	private final CanaryMapper mapper;

	public JdbcCanaryStore(CanaryMapper mapper) {
		this.mapper = mapper;
		log.info("灰度配置就绪（表 prompt_canary / 观测读 agent_step，均由 spring.sql.init 建好）");
	}

	// ------------------------------------------------------------------
	//  配置侧
	// ------------------------------------------------------------------

	@Override
	public Optional<Row> active(PromptKind kind) {
		MapperRows.CanaryRow row = read(() -> mapper.activeOfKind(kind), "读进行中的灰度配置失败 kind=" + kind);
		return row == null ? Optional.empty() : Optional.of(row.toDomain());
	}

	@Override
	public Optional<Row> latest(PromptKind kind) {
		// 一份提示词只有一行（主键就是 kind），所以 latest 与"那一行"是同一个东西 ——
		// 名字保留是因为调用方问的是"上一轮实验是什么"，而它读的是同一行
		MapperRows.CanaryRow row = read(() -> mapper.latestOfKind(kind), "读灰度配置失败 kind=" + kind);
		return row == null ? Optional.empty() : Optional.of(row.toDomain());
	}

	@Override
	public Row start(PromptKind kind, int candidateVersion, int weightPercent, String note, String by, Instant now) {
		// 【并发保护：让主键来仲裁】
		// 先查 active 再 INSERT 是典型的 check-then-act，两个人都点"开始"时会同时通过检查。
		// 这里直接 INSERT，撞主键就是"已经有一条"—— 数据库不会漏判。
		try {
			mapper.insert(kind, candidateVersion, weightPercent, by, now, note);
		}
		catch (RuntimeException e) {
			if (isDuplicate(e)) {
				throw new CanaryConflictException("这一份提示词已经有一次进行中的灰度了 —— "
						+ "先停掉它（停止时要写清为什么停），再开新的。"
						+ "⚠️ 不允许直接覆盖：被覆盖掉的那次是一次正在进行的实验，"
						+ "而覆盖之后它从历史上就消失了");
			}
			throw new CanaryStoreException("开灰度失败 kind=" + kind, e);
		}
		return latest(kind).orElseThrow(
				() -> new CanaryStoreException("刚写入的灰度配置读不回来 kind=" + kind));
	}

	/**
	 * 判"是不是主键冲突"。
	 *
	 * <h2>与迁移前相比，这里多了一层判据、少了一层耦合</h2>
	 * 原先只能看驱动抛出的 {@link SQLException} 的 SQLState / 错误码。现在多了
	 * {@link DataIntegrityViolationException} 这一层：Spring 的异常翻译会把
	 * 标准 SQLState {@code 23505}（唯一约束冲突）转成 {@code DuplicateKeyException}
	 * （它是 {@code DataIntegrityViolationException} 的子类），于是判据不再依赖驱动细节。
	 *
	 * <p>但**底层的 SQLState 判据一条都没有删**，因为存在翻译不到的情况：
	 * H2 的 {@code 23000} 与 MySQL 的 {@code 1062} 都不在 Spring 的"重复键"表里，
	 * 它们会落到 {@code DataIntegrityViolationException}（仍然被上面那一层接住）
	 * 或者干脆以原始 {@link SQLException} 的形式冒出来（由下面这一段接住）。
	 * 三档判据并存不是冗余：**这个判断失效时的表现是"并发开灰度时回 500 而不是 409"，
	 * 而它只在并发下出现、平时测不到**。
	 *
	 * <p>为什么按 SQLState / 错误码判，而不是按异常消息里有没有 "Duplicate"：
	 * 消息文本是 H2 与 MySQL 各自的实现细节，它一旦变了这里的判断会静默失效。所以判据用标准的东西：
	 * <ul>
	 *   <li>{@code 23505} —— SQL 标准的唯一约束冲突（H2 用的就是它）；</li>
	 *   <li>{@code 23000} —— 完整性约束冲突的父类（真 MySQL 在这个码下面）；</li>
	 *   <li>{@code 1062} —— MySQL 的 "Duplicate entry" 错误码（留作兜底）。</li>
	 * </ul>
	 * ⚠️ ⚠️ 前两条与第三条**都已经在 H2 上实测过**（"同时开两次只有一次成功"走的就是这条路）；
	 * 但**真 MySQL 那一档没有实测过**（本项目没接真 MySQL）——
	 * 所以这里只声称"两种码都认"，不声称"在 MySQL 上也验过"。
	 */
	private static boolean isDuplicate(RuntimeException e) {
		if (e instanceof DataIntegrityViolationException) {
			return true;
		}
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof SQLException sql) {
				String state = sql.getSQLState();
				if ("23505".equals(state) || "23000".equals(state) || sql.getErrorCode() == 23505
						|| sql.getErrorCode() == 1062) {
					return true;
				}
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return false;
	}

	@Override
	public Optional<Row> stop(PromptKind kind, String endReason, String by, Instant now) {
		int affected = read(() -> mapper.stop(kind, endReason, by, now), "停灰度失败 kind=" + kind);
		if (affected == 0) {
			// 没有进行中的那条 —— 由服务层回 409，见接口注释
			return Optional.empty();
		}
		return latest(kind);
	}

	// ------------------------------------------------------------------
	//  数据侧（观测）
	// ------------------------------------------------------------------

	@Override
	public Observation observe(int days) {
		int effective = Math.max(1, Math.min(days, 90));
		LocalDate today = LocalDate.now(ZoneId.systemDefault());
		LocalDate from = today.minusDays(effective - 1L);
		Instant since = from.atStartOfDay(ZoneId.systemDefault()).toInstant();

		// 【三个计数来自两条 GROUP BY，而不是一条"大而全"的查询】
		// 一条查询里同时 GROUP BY 指纹与变体，再想把"按变体的运行数"加起来，
		// 就依赖一个假设：同一次运行不会同时出现在两个变体里。
		// 那个假设**今天成立**（一次运行只读一次提示词），但它是一条隐含前提 ——
		// 而隐含前提在下一次改动时就会静默失效（这里失效的表现是参与数翻倍）。
		// 所以这里让数据库分别在两个粒度上各算一次：多一条 SQL，少一个假设
		List<RunVersion> versions = new ArrayList<>();
		// canary_variant 这一列是随本件一起加的，老库里可能没有 —— 那是"部署没完成"，
		// 必须炸而不是降级：降级会让观测报告安静地少一半数据（错误消息里点明这一条）
		for (MapperRows.CanaryRunBucketRow bucket : read(() -> mapper.runsByFingerprintAndVariant(since),
				"观测灰度分流失败（canary_variant 列存在吗？见 db/agent-schema.sql）")) {
			versions.add(new RunVersion(versionOf(bucket.fingerprint()), bucket.variant(), bucket.runs()));
		}

		int candidate = 0;
		int stable = 0;
		int participating = 0;
		int runs = 0;
		for (MapperRows.CanaryRunBucketRow bucket : read(() -> mapper.runsByVariant(since), "观测灰度分流失败")) {
			int count = bucket.runs();
			runs += count;
			if (CanaryDecision.LABEL_CANARY.equals(bucket.variant())) {
				candidate += count;
				participating += count;
			}
			else if (CanaryDecision.LABEL_STABLE.equals(bucket.variant())) {
				stable += count;
				participating += count;
			}
		}
		return new Observation(runs, participating, candidate, stable, versions);
	}

	/**
	 * 从指纹里解析版本号（形如 {@code v4:9ab12cd34ef5}）。
	 *
	 * <p>解析不出来返回 0 —— 而不是丢行。丢掉会让"总运行数"与"逐版本之和"对不上，
	 * 而两个数字对不上时，读的人只会信那个大的（页面上更显眼的那个）。
	 */
	static int versionOf(String fingerprint) {
		if (fingerprint == null || fingerprint.length() < 2 || fingerprint.charAt(0) != 'v') {
			return 0;
		}
		int colon = fingerprint.indexOf(':');
		if (colon <= 1) {
			return 0;
		}
		try {
			return Integer.parseInt(fingerprint.substring(1, colon));
		}
		catch (NumberFormatException e) {
			return 0;
		}
	}

	/** 读操作的异常包装（配置侧与观测侧共用）。 */
	private <T> T read(java.util.function.Supplier<T> call, String whatFailed) {
		try {
			return call.get();
		}
		catch (DataAccessException e) {
			throw new CanaryStoreException(whatFailed, e);
		}
	}

}


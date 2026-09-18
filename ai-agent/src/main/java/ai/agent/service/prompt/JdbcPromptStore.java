package ai.agent.service.prompt;

import ai.agent.entity.prompt.PromptVersion;
import ai.agent.mapper.MapperRows;
import ai.agent.mapper.PromptMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link PromptStore} 的存储实现 —— <b>SQL 在 {@link PromptMapper} 里，本类只管语义</b>。
 *
 * <h2>为什么本类里还留着 {@code synchronized}（而不是交给数据库）</h2>
 * 版本号是"当前最大 + 1"，而"读最大 → 写新行"这两步之间若被插进来另一次保存，
 * 两次就会算出同一个号，其中一条被主键挡掉 ——
 * 表现为"保存失败了，但错误看着像数据库问题"。
 * 数据库侧本来可以用 {@code SELECT MAX(...) FOR UPDATE}，但那个写法在 H2 与 MySQL 上
 * 行为不完全一样（本项目的存储层刻意只依赖两者的交集）。
 * 提示词保存是<b>低频人工操作</b>（一天可能就几次），用一把锁换掉这一类不确定性是划算的。
 *
 * <p>⚠️ 它的有效性依赖"调用的是被 Spring 代理前的那把锁"，所以这个方法**不能在
 * 本类内部被自调用**（自调用会绕过代理，但锁在同一个对象上，仍然有效）——
 * 真正会失效的是"有人把本 bean 拆成两个实例"，那时两个实例各有各的锁。
 *
 * <h2>建表与连接池：本类不再管了</h2>
 * 表由 {@code spring.sql.init} 建（脚本清单在 {@code application.properties}），
 * 连接由全应用唯一的 {@code DataSource} 提供。
 * 原先这里通过 {@code JdbcAgentRunStore.pool()} 借池 —— 那个方法已删除：
 * 借池是"没有统一 DataSource"时的权宜做法，现在由 Spring 注入，池的生命周期只有一处。
 */
@Component
public class JdbcPromptStore implements PromptStore {

	private static final Logger log = LoggerFactory.getLogger(JdbcPromptStore.class);

	private final PromptMapper mapper;

	public JdbcPromptStore(PromptMapper mapper) {
		this.mapper = mapper;
		log.info("Prompt 版本库就绪（表 prompt_version / prompt_active 由 spring.sql.init 建好）");
	}

	// ------------------------------------------------------------------
	//  读
	// ------------------------------------------------------------------

	@Override
	public List<PromptVersion> versionsOf(PromptKind kind) {
		return read(() -> mapper.listByKind(kind).stream().map(MapperRows.PromptVersionRow::toDomain).toList(),
				"读提示词版本列表失败 kind=" + kind);
	}

	@Override
	public Optional<PromptVersion> find(PromptKind kind, int version) {
		MapperRows.PromptVersionRow row = read(() -> mapper.findByKindAndVersion(kind, version),
				"读提示词版本失败 kind=" + kind + " version=" + version);
		return row == null ? Optional.empty() : Optional.of(row.toDomain());
	}

	@Override
	public Optional<PromptVersion> active(PromptKind kind) {
		MapperRows.PromptVersionRow row = read(() -> mapper.activeOfKind(kind), "读当前生效的提示词失败 kind=" + kind);
		return row == null ? Optional.empty() : Optional.of(row.toDomain());
	}

	// ------------------------------------------------------------------
	//  写
	// ------------------------------------------------------------------

	/**
	 * 追加一版（理由见类注释：{@code synchronized} 在这里是有意的）。
	 */
	@Override
	public synchronized PromptVersion save(PromptKind kind, String content, String note, String author,
			Integer forkedFrom, int tokenEstimate) {
		int next = read(() -> mapper.maxVersion(kind), "读最大版本号失败 kind=" + kind) + 1;
		try {
			mapper.insertVersion(kind, next, content, PromptVersion.hashOf(content),
					// char_count 单独存一列而不是每次现算：它记的是**当时**那份正文的长度
					content == null ? 0 : content.length(), tokenEstimate, note, author, forkedFrom);
		}
		catch (DataAccessException e) {
			throw new PromptStoreException("保存提示词新版本失败 kind=" + kind + " version=" + next, e);
		}
		// 写完立刻读回来：返回的是**库里的那一行**（含 created_at 与 hash），
		// 而不是"我以为写进去的东西" —— 两者不一致时应该现在就知道
		return find(kind, next).orElseThrow(
				() -> new PromptStoreException("刚写入的版本读不回来 kind=" + kind + " version=" + next));
	}

	/**
	 * 切换生效版本 —— <b>一条带前置条件的 UPDATE</b>。
	 *
	 * <p>并发语义落在 SQL 的 {@code AND version = ?} 上（见 {@link PromptMapper#updatePointer}），
	 * 而不是"先查一次再判断"：后者是典型的 check-then-act，两个线程可以同时通过检查。
	 * 这里让数据库来仲裁 —— 受影响行数为 0 就说明前提过期了。
	 *
	 * <p>本方法前面的两次读（{@code find} / {@code active}）是**便宜的前置过滤**，
	 * 不是并发保证：真正的判据是最后那个受影响行数。
	 */
	@Override
	public Optional<PromptVersion> activate(PromptKind kind, int version, Integer expectedVersion) {
		if (find(kind, version).isEmpty()) {
			return Optional.empty();
		}
		Optional<PromptVersion> current = active(kind);
		if (expectedVersion != null && current.isPresent() && current.get().version() != expectedVersion) {
			return Optional.empty();
		}
		int affected;
		try {
			affected = current.isEmpty() ? mapper.activateFirst(kind, version)
					: mapper.updatePointer(kind, version, current.get().version());
		}
		catch (DataAccessException e) {
			throw new PromptStoreException("切换生效提示词失败 kind=" + kind + " version=" + version, e);
		}
		if (affected == 0) {
			// 走到这里说明在"读 current"与"写"之间有人动过指针 —— 前提已过期，
			// 这一次切换**没有执行**。它必须与"切换成功"区分开：静默成功会覆盖别人的决定
			return Optional.empty();
		}
		return active(kind);
	}

	/** 读操作的异常包装（五个方法共用）。 */
	private <T> T read(Supplier<T> call, String whatFailed) {
		try {
			return call.get();
		}
		catch (DataAccessException e) {
			throw new PromptStoreException(whatFailed, e);
		}
	}

}

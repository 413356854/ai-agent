package ai.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.autoconfigure.init.ApplicationScriptDatabaseInitializer;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>启动时核对知识库三张表的形状</b>——表在不在、本代码要用的列在不在。
 *
 * <p>做法与 {@link SkillSchemaCheck} / {@link PermissionSchemaCheck} / {@link TaskSchemaCheck} 逐条相同
 * （那边写清了它接住的是什么：{@code CREATE TABLE IF NOT EXISTS} 只保证表存在、不保证表是新的，
 * 于是"库里有一张旧表"的报错会以运行期 {@code Unknown column} 的形式出现在离原因很远的地方），
 * 这里只在"拒绝启动时给出的处理方式"上按这三张表说话。
 *
 * <h2>为什么不合并进另外三个检查</h2>
 * 与 {@link SkillSchemaCheck} 的理由一致：几个检查各自对着"重建代价"说话，
 * 而三种表的代价不同。知识库这三张尤其特殊 ——
 * {@code kb_chunk} 是**一份清单**，删掉它这一侧的代价不是"重新建一遍"，
 * 而是要和 Milvus 里的向量重新对齐（走"重新索引"）。
 */
@Component
public class KnowledgeSchemaCheck implements InitializingBean {

	private static final Logger log = LoggerFactory.getLogger(KnowledgeSchemaCheck.class);

	/**
	 * 每张表必须存在的列。
	 *
	 * <p>列的是<b>本代码会读写的那些</b>：多出来的列无害，而少一列就会在某个查询上炸。
	 */
	private static final Map<String, List<String>> REQUIRED = new LinkedHashMap<>();

	static {
		REQUIRED.put("knowledge_base", List.of("id", "name", "description", "embedding_provider",
				"embedding_model", "embedding_dim", "collection_name", "chunk_strategy", "top_k",
				"similarity_threshold", "rerank_enabled", "enabled", "created_at", "updated_at"));
		REQUIRED.put("kb_document", List.of("id", "kb_id", "name", "format", "size_bytes", "source_path", "status",
				"enabled", "chunk_count", "token_count", "error", "indexed_at", "created_at", "updated_at"));
		REQUIRED.put("kb_chunk", List.of("id", "uid", "kb_id", "document_id", "seq", "text", "heading_path",
				"token_count", "char_count", "enabled", "created_at", "updated_at"));
	}

	private final DataSource dataSource;

	/** 只用于声明顺序（本类不调它的任何方法），见 {@link SkillSchemaCheck} 的类注释。 */
	public KnowledgeSchemaCheck(DataSource dataSource, ApplicationScriptDatabaseInitializer scripts) {
		this.dataSource = dataSource;
	}

	@Override
	public void afterPropertiesSet() {
		Map<String, String> problems = new LinkedHashMap<>();
		try (Connection connection = dataSource.getConnection()) {
			for (Map.Entry<String, List<String>> entry : REQUIRED.entrySet()) {
				List<String> columns = columnsOf(connection, entry.getKey());
				if (columns.isEmpty()) {
					problems.put(entry.getKey(), "表不存在");
					continue;
				}
				List<String> missing = entry.getValue().stream()
						.filter(required -> !columns.contains(required))
						.toList();
				if (!missing.isEmpty()) {
					problems.put(entry.getKey(), "缺少列 " + missing + "（现有：" + columns + "）");
				}
			}
		}
		catch (SQLException e) {
			throw new IllegalStateException("核对知识库表结构失败 —— 库连上了但元数据读不出来", e);
		}

		if (!problems.isEmpty()) {
			throw new IllegalStateException(("知识库表结构与代码对不上（脚本 db/knowledge-schema.sql 只建缺的表，"
					+ "**不会改已经存在的表**）：%s。"
					+ "处理方式二选一：① 删掉 knowledge_base / kb_document / kb_chunk 再启动一次，脚本会按最新定义重建；"
					+ "② 手工 ALTER TABLE 补上缺的列。"
					+ "⚠️ 走第一条之前先清 Milvus 里的 kb_* 集合 —— 表没了之后集合名就查不到了，"
					+ "剩下的那些集合没有任何东西引用它们（集合名在 knowledge_base.collection_name 里）")
					.formatted(problems));
		}
		log.info("知识库表结构核对完成：{} 张表、{} 个承重列都在", REQUIRED.size(),
				REQUIRED.values().stream().mapToInt(List::size).sum());
	}

	/** 一张表的列名（小写）。表不存在时返回空表 —— 由调用方把"表不存在"与"缺列"分开报。 */
	private static List<String> columnsOf(Connection connection, String table) throws SQLException {
		DatabaseMetaData metaData = connection.getMetaData();
		Map<String, String> columns = new HashMap<>();
		// 表名大小写：MySQL 在 Windows 上按定义原样返回（小写），H2 的元数据在
		// DATABASE_TO_LOWER=TRUE 下也是小写。两种写法都问一遍，省得依赖驱动的返回习惯
		for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT))) {
			try (ResultSet rs = metaData.getColumns(connection.getCatalog(), null, candidate, "%")) {
				while (rs.next()) {
					String name = rs.getString("COLUMN_NAME");
					columns.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
				}
			}
			if (!columns.isEmpty()) {
				break;
			}
		}
		return List.copyOf(columns.keySet());
	}

}

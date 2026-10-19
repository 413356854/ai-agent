-- ============================================================================
--  知识库（向量检索 · Milvus）
--
--  【这三张表要回答的问题】
--  knowledge_base：一个可检索的语料集合 —— 它用哪个 embedding 模型、多少维、
--                  落在 Milvus 的哪个集合里、默认召回几条。
--  kb_document：   上传进来的原始文档 —— 索引到哪一步了、切出几块、失败没有。
--  kb_chunk：      切片清单 —— 每块的正文、在文档里的序号、标题路径、以及"这一块还参不参与检索"。
--
--  【向量存在 Milvus，清单存在这里 —— 为什么不是一份】
--  admin 那边切片只存向量库（ES），因为 ES 本身就是文档库：分页、排序、取字段都现成。
--  Milvus 是相似度索引，不是文档库，而 Spring AI 在它上面只暴露 add / delete / search（无更新）。
--  于是"翻一个 enabled 位"在 Milvus 侧要先查出旧向量、再整行 upsert（SDK 2.5.8 没有局部更新，
--  只剩 insert / upsert 两条整行写入）—— 一个布尔开关变成一次向量重写。
--  所以：**Milvus 只存向量与 doc_id（重建索引、删文档时按 doc_id 整批删），
--  正文、序号、标题路径、enabled 都在这三张表里**。
--  检索返回的是 uid，再用它回表取正文并按 enabled 过滤。
--  ⚠️ 代价说清：Milvus 里的条数与 kb_chunk 的条数**可能不一致**（删到一半进程退出、
--     手工动过库）。两者的比对端点见 GET /api/knowledge-bases/{id}/consistency。
--
--  【三态：没索引过 / 索引了但一块没切出来 / 索引失败】
--  这三种情况在只看一个字段时长得一样，所以用三个字段分开：
--    indexed_at IS NULL                             → 从没索引成功过
--    index_status = processed 且 chunk_count = 0    → 索引成功，但一个块都没切出来（扫描件、空文件）
--    index_status = failed                          → 失败，原因在 error
--  ⚠️ 中间的 `processed + 0` 是**正常终态**，不是失败：界面上必须写成
--    「已索引，但没有切出任何块」，与灰色的「未索引」区分开。
--    admin 那边 chunk_count 没有这个区分，0 与"没索引"是同一个样子。
--
--  【embedding 的三个字段是快照，不是实时读配置】
--  embedding_provider / embedding_model / embedding_dim 在建库那一刻写死。
--  集合维度在建集合时就定了，之后换模型等于换一个向量空间 ——
--  与 VectorIndex 的指纹防线同一条理由（ai.agent.service.rag.vector.VectorIndex）。
--  ⚠️ 所以改 application.properties 里的 ai.rag.kb.embedding.* **不影响已有知识库**；
--     要对已有库换模型，只有"建一个新库、重新上传"这一条路。
--
--  【检索参数（top_k / similarity_threshold / rerank_enabled）为什么是列不是 JSON】
--  它们是三个标量、有明确默认值、要能被校验（top_k ≥ 1）。塞进一个 TEXT 列之后，
--  "读出来是 null"与"配置里没写"就分不开了，而这两者的处理方向相反。
--  分块参数不在这里：它沿用 ai.rag.chunking.* 的当前值（每个库一份参数在 v1 里没有需求）。
--
--  【名字唯一】
--  ⚠️ knowledge_base.name 是唯一键：界面上按名字找人，重名会让"我删的是哪一个"没有答案。
--  重复时写入路径回 409，不是 400（见 KnowledgeBaseController 的 @ExceptionHandler）。
--
--  【写法贴 MySQL】
--  与 task-schema.sql / schedule-schema.sql 同族：AUTO_INCREMENT / TINYINT /
--  TIMESTAMP DEFAULT CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP / 建表末尾 COMMENT='…'。
--  ⚠️ 表注释只能写在 CREATE TABLE 末尾（ALTER TABLE t COMMENT 只有 MySQL 认，H2 报 42001）。
--  ⚠️ 每一列都带 COMMENT：字段含义写在 COMMENT 里，设计取舍写在列上方。
--
--  【索引不在这里建】见 ai.agent.config.SchemaIndexInitializer 的 DEFINITIONS：
--  MySQL 8.0 不认 `CREATE INDEX IF NOT EXISTS`，写在脚本里会让第二次启动直接失败。
-- ============================================================================


-- 一个知识库（= Milvus 里一个集合）
CREATE TABLE IF NOT EXISTS knowledge_base (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '知识库 id',
  -- 界面上用来认人。唯一键的理由见头注
  name              VARCHAR(128) NOT NULL COMMENT '知识库名称（唯一）',
  description       VARCHAR(512) NULL COMMENT '说明；NULL 显示为「（未填说明）」',
  -- 建库时的 embedding 快照（见头注）。provider 目前只有 dashscope
  embedding_provider VARCHAR(32) NOT NULL COMMENT 'embedding 提供方（建库时定死）：dashscope',
  embedding_model   VARCHAR(128) NOT NULL COMMENT 'embedding 模型名（建库时定死），例 text-embedding-v4',
  embedding_dim     INT          NOT NULL COMMENT '向量维度（建库时定死，且等于 Milvus 集合的维度）',
  -- Milvus 集合名：建库时生成，之后不可改（改它等于指向另一个集合）
  collection_name   VARCHAR(255) NOT NULL COMMENT 'Milvus 集合名（建库时生成，之后不可改）',
  -- 分块策略：length（定长字符）/ structural（按标题结构，带面包屑）
  chunk_strategy    VARCHAR(32)  NOT NULL COMMENT '分块策略：length / structural',
  -- 检索参数的默认值（每次检索可用请求参数覆盖）
  top_k             INT          NOT NULL DEFAULT 5 COMMENT '默认召回条数',
  similarity_threshold DOUBLE    NOT NULL DEFAULT 0.4 COMMENT '默认相似度阈值（余弦；低于它不返回）',
  rerank_enabled    TINYINT      NOT NULL DEFAULT 0 COMMENT '默认是否重排：1 是 / 0 否（重排要多一次 DashScope 调用）',
  -- 停用：不在检索范围内（列表照常看得到，只是不参与召回）
  enabled           TINYINT      NOT NULL DEFAULT 1 COMMENT '启用状态：1 启用 / 0 停用（停用的库不参与检索）',
  created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_knowledge_base_name UNIQUE (name)
) COMMENT='知识库';


-- 一份上传进来的文档
CREATE TABLE IF NOT EXISTS kb_document (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文档 id',
  kb_id         BIGINT       NOT NULL COMMENT '所属知识库（knowledge_base.id）',
  -- 上传时的原始文件名（展示用）。落盘用的名字是 id + 扩展名，不含用户输入
  name          VARCHAR(255) NOT NULL COMMENT '原始文件名（展示用）',
  format        VARCHAR(16)  NOT NULL COMMENT '扩展名（小写），决定用哪个解析器',
  size_bytes    BIGINT       NOT NULL DEFAULT 0 COMMENT '文件字节数',
  -- 落盘位置：{ai.rag.kb.storage-path}/{kbId}/{docId}.{ext}
  source_path   VARCHAR(512) NOT NULL COMMENT '落盘位置（相对 storage-path）',
  -- 索引状态：UPLOADED（已入库未处理）/ PROCESSING（正在处理）/ PROCESSED（成功）/ FAILED（失败）。
  -- 存枚举名而不是序号：序号与枚举的声明顺序绑死，加一个取值就会让历史行的含义整体错位
  status        VARCHAR(32)  NOT NULL DEFAULT 'UPLOADED' COMMENT '索引状态：UPLOADED / PROCESSING / PROCESSED / FAILED',
  -- 停用：不参与检索。它不改切片行 —— 检索侧按 doc_id 过滤这一列
  enabled       TINYINT      NOT NULL DEFAULT 1 COMMENT '启用状态：1 启用 / 0 停用（停用的文档不参与检索）',
  -- 上次索引成功切出的块数。0 是**正常值**（见头注的三态），不是"没数据"
  chunk_count   INT          NOT NULL DEFAULT 0 COMMENT '上次索引切出的块数；0 是正常终态（解析完一块都没切出来）',
  token_count   INT          NOT NULL DEFAULT 0 COMMENT '上次索引切出的估算 token 总数',
  -- 失败原因原文；成功时为 NULL
  error         VARCHAR(1024) NULL COMMENT '失败原因原文；成功时为 NULL（NULL 与空串含义不同）',
  -- 从没索引成功过时为 NULL（见头注的三态）
  indexed_at    TIMESTAMP    NULL COMMENT '上次索引成功的时间；NULL = 从没成功过',
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间（状态变化会刷新它）',
  PRIMARY KEY (id)
) COMMENT='知识库文档';


-- 切片清单（Milvus 里对应那一行的主键是这里的 uid）
CREATE TABLE IF NOT EXISTS kb_chunk (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '切片 id（SQL 主键，用于排序与关联）',
  -- Milvus 里的主键。**为什么不在 Java 里直接用上面的自增 id**：
  -- 本项目的驱动组合下 MyBatis 的三条"自增主键回填"路都不通（见 AgentTaskMapper 的类注释，
  -- 失效时不抛异常、只留一个 null），而切片是一次几十上百条地批量插入 ——
  -- 要么为了拿 id 改成一条一条插（N 次往返），要么自己生成一个主键。
  -- 取后者：uid 由 Java 生成，批量插入一条 SQL 就够，两个库用同一个键对得上。
  uid           VARCHAR(64)  NOT NULL COMMENT 'Milvus 集合里的主键（Java 生成），两个库靠它对齐',
  kb_id         BIGINT       NOT NULL COMMENT '所属知识库（knowledge_base.id）',
  document_id   BIGINT       NOT NULL COMMENT '所属文档（kb_document.id）',
  -- 文档内序号，从 0 开始。重新索引后序号会重排（切分方式变了），而 id 也会换
  seq           INT          NOT NULL COMMENT '文档内序号（从 0 开始）',
  text          TEXT         NOT NULL COMMENT '切片正文（送进 embedding 的就是它）',
  heading_path  VARCHAR(512) NULL COMMENT '标题路径，例「四、踩坑知识库 > 2.3」；无标题为 NULL',
  token_count   INT          NOT NULL DEFAULT 0 COMMENT '估算 token 数',
  char_count    INT          NOT NULL DEFAULT 0 COMMENT '字符数',
  -- 检索侧按它过滤（Milvus 不存这一位，见头注）
  enabled       TINYINT      NOT NULL DEFAULT 1 COMMENT '启用状态：1 参与检索 / 0 不参与',
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间（改正文会刷新它）',
  PRIMARY KEY (id),
  CONSTRAINT uk_kb_chunk_uid UNIQUE (uid)
) COMMENT='知识库切片';

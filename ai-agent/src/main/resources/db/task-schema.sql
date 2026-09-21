-- ============================================================================
--  Agent 任务与确认项（应用管理区 · agent任务）
--
--  【这两张表要回答的问题】
--  一次"派给 Agent 的活"从发布到验收要经过几个人的确认，而在它们之前
--  这件事在系统里无处落点：
--    ① 谁派的、派给谁、现在轮到谁（发布人 / 任务人 / 当前执行人）；
--    ② 卡在哪一步（需求 / 计划 / 授权 / 执行 / 验收）；
--    ③ 每一步当时给的是什么选项、他选/答的是什么（用户确认项）。
--
--  【状态链：八个取值，每次推进一格】
--    新发布 NEW
--      → 待确认需求 PENDING_REQUIREMENT   （第 1 步，生成一条需求确认项）
--      → 待确认计划 PENDING_PLAN          （第 2 步，生成一条计划确认项）
--      → 待授权     PENDING_AUTHORIZATION （第 3 步，生成一条授权确认项）
--      → 执行中     EXECUTING             （第 4 步，执行人是 agent）
--      → 待验收     PENDING_ACCEPTANCE    （第 5 步）
--      → 已完成     COMPLETED             （第 6 步）
--    任一非终态都可 → 已取消 CANCELLED（取消原因与状态同一次写入）
--
--  【为什么三个"待确认"是三个状态，而不是一个「待确认」】
--  一个状态装不下"现在等的是谁、等的是什么"这两个问题的答案，
--  而这两件事决定界面上该把卡片放在哪一列、该弹什么按钮。
--  合成一个之后，唯一的表达方式是"再看看它挂了哪条确认项" —— 那是把判据散进查询。
--
--  【为什么确认项是一张子表，而不是任务表上的一列 JSON】
--  一次任务会有多条确认项（每步一条），每条有自己的选项集与答复。
--  挤进一列只能存一个 JSON 数组，而"这一条到底答没答"要靠解析 ——
--  解析失败与"还没有答"在界面上长得一样，而处置完全不同。
--
--  【选项集为什么是 TEXT 存 JSON 数组，而不是第三张表】
--  它是一次性展示给人点的几个按钮（["确认","需要补充"]）：没有身份、不被单独引用、
--  也不参与任何 SQL 过滤（没有查询会 `WHERE 选项 = …`）—— 所以"TEXT 存 JSON 不好查"
--  这条常见代价在这里不成立。列名用 option_set 而不是 options：`OPTIONS` 是 SQL 方言里
--  常见的保留字（MySQL 的表选项、H2 的关键字表都有它），而报错位置在 SQL 解析层、
--  离调用点很远（与 permissions-schema.sql 里 access_scope 的取名同一条理由）。
--  ⚠️ 也没有用 MySQL 的 JSON 列类型：H2 的 MODE=MySQL 下 TEXT 是两种库都认的写法。
--
--  【为什么没有 delete，只有"已取消"】
--  与 agent_user 同一条规矩：删掉一行会让"当时为什么取消"这个问题失去主语。
--  取消保留整条任务与它的全部确认项，取消原因与状态同一次写入（见 agent_task.cancel_reason）。
--
--  【写法贴 MySQL】
--  与 agent-schema.sql / permissions-schema.sql 同族：AUTO_INCREMENT /
--  TIMESTAMP DEFAULT CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP /
--  外键 ON DELETE CASCADE / 建表语句末尾的 COMMENT='…'。
--  ⚠️ 表注释只能写在 CREATE TABLE 末尾（`ALTER TABLE t COMMENT='…'` 只有 MySQL 认，H2 报 42001）。
--
--  【索引不在这里建】见 ai.agent.config.SchemaIndexInitializer 的 DEFINITIONS：
--  MySQL 8.0 不认 `CREATE INDEX IF NOT EXISTS`，写在脚本里会让第二次启动直接失败。
-- ============================================================================

-- 一次派给 Agent 的任务
CREATE TABLE IF NOT EXISTS agent_task (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  -- 发布人：指派出任务的人（姓名快照，不是 agent_user 的外键）
  --
  -- 【为什么是快照而不是外键】这一列要回答的是"当时是谁派的"，
  -- 而账号是可以改显示名、可以停用的 —— 外键会让这条任务在账号改名后显示成新名字，
  -- 于是"当时"这个语义丢了。与 agent_run.decided_by / granted_by 同一条规矩。
  publisher        VARCHAR(128) NOT NULL,
  -- 任务人：交付任务的人
  assignee         VARCHAR(128) NULL,
  -- 任务描述：这次要做什么。它同时也是生成第一条确认项的输入
  description      TEXT         NOT NULL,
  -- 状态取值见头注的状态链，库里的值一律是枚举名（AgentTaskStatus）。
  -- ⚠️ 读侧对认不出的取值落在 NEW 而不是"某一个看起来正常的中间态"：
  -- 落成终态会让这一行从看板的活跃列里消失，而"卡片不见了"是最难发现的一种坏法
  status           VARCHAR(32)  NOT NULL DEFAULT 'NEW',
  -- 当前执行人：这条任务现在流转到谁手里。
  -- EXECUTING 时写 agent（形如 agent#3，3 是 agent_id）；其余是人的名字（发布人或任务人）
  current_executor VARCHAR(128) NULL,
  -- 任务当前步骤，从 1 开始：1 需求 / 2 计划 / 3 授权 / 4 执行 / 5 验收 / 6 完成。
  -- 新发布也记 1 —— 它表示"停在第 1 步还没开始"，而不是第 0 步（界面上没有第 0 步这个东西）
  current_step     INT          NOT NULL DEFAULT 1,
  -- 任务交给哪个 Agent 应用。**不做外键**：Agent 应用表在另一个业务域里，
  -- 而这里只需要一个引用键（与 agent_run.intent / tenant_id 同一条规矩：
  -- 归因键的取值由业务侧定义，数据库管不了它有多少个）
  agent_id         BIGINT       NULL,
  -- 执行结果：执行那一步走完时登记（EXECUTING → 待验收）
  result           TEXT         NULL,
  -- 取消原因：用户取消时填写，与 status=CANCELLED 同一次写入。
  -- 允许 NULL 只为了非取消状态的那一行；取消时由写入路径强制非空（服务层唯一入口）
  cancel_reason    VARCHAR(512) NULL,
  created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) COMMENT='agent任务';

-- 一条确认项：任务在第几步问了什么、给了哪些选项、用户答了什么
CREATE TABLE IF NOT EXISTS agent_task_confirm (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  task_id      BIGINT      NOT NULL,
  -- 任务步骤：这一条是在任务的第几步提出来的（值来自 agent_task.current_step，
  -- 即"第 1/2/3 步"= 需求 / 计划 / 授权三种确认）。列轴与 agent_step.step_index 同名同义
  step_index   INT         NOT NULL,
  -- 类型：REQUIREMENT 需求确认 / PLAN 计划确认 / AUTHORIZATION 授权确认
  confirm_type VARCHAR(32) NOT NULL,
  -- 确认事项描述：要用户确认的那件事
  description  TEXT        NULL,
  -- 选项集：JSON 数组，如 ["确认","需要补充"]。空/null = 只收自由回复
  option_set   TEXT        NULL,
  -- 用户确认项：用户选的选项或自由回复；NULL = **还没答**。
  -- "空串"与 NULL 在这里是两件事：空串是一次"答了但什么都没写"，会被拒绝在写入路径上
  answer       TEXT        NULL,
  created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  -- 同一步骤同一种类型只允许一条：并发推进时第二次插入被数据库拦下，
  -- 而不是在界面上出现两条一模一样的确认项（然后用户只能猜该答哪一条）
  CONSTRAINT uk_agent_task_confirm_step UNIQUE (task_id, step_index, confirm_type),
  -- CASCADE：任务没了，它的确认项就没有主语了（与 agent_user_role 同一条理由）
  CONSTRAINT fk_agent_task_confirm_task FOREIGN KEY (task_id) REFERENCES agent_task (id) ON DELETE CASCADE
) COMMENT='agent任务确认项';

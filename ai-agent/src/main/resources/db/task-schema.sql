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
--  【状态链：十二个取值，每次推进一格】
--    新发布 NEW
--      → 待确认需求 PENDING_REQUIREMENT   （生成一条需求确认项，等答复）
--      → 已确认需求 CONFIRMED_REQUIREMENT （球交给 agent：再判一次歧义、拆任务点）
--      → 待确认计划 PENDING_PLAN          （任务点 ≥ 阈值时才有这一格，等答复）
--      → 已确认计划 CONFIRMED_PLAN        （球交给 agent：判断授权）
--      → 待授权     PENDING_AUTHORIZATION （授权判断通过，等答复）
--      → 已授权     AUTHORIZED            （球交给 agent：执行）
--      → 执行中     EXECUTING             （跑完写执行结果）
--      → 待验收     PENDING_ACCEPTANCE
--      → 已完成     COMPLETED
--    已确认计划 / 已授权 的判断不通过 → 权限不足 INSUFFICIENT_PERMISSION（结论写进 result，等处置）
--    权限不足 ── 人答复「已修改任务」──→ 新发布（重新走一遍）
--    任一非终态都可 → 已取消 CANCELLED（取消原因与状态同一次写入）
--
--  【三个"人"的列存的是用户 id，不是姓名】
--    publisher_id / assignee_id / current_executor_id 三列都是 agent_user.id。
--    存 id 的理由是"当时是谁"要一直指向同一个人，而姓名是可以改的：存姓名的话，
--    账号改名之后要么这条记录挂着一个查不到的名字，要么得把老记录一起改掉 ——
--    前者让那张卡片上是一个不存在的人，后者改掉了历史。存 id 则两条都成立：
--    指向谁不变，显示的名字取账号表**当前**的写法（读侧 LEFT JOIN agent_user，见 AgentTaskMapper.xml）。
--    ⚠️ 读侧一律 LEFT JOIN 而不是 INNER JOIN：账号那一行万一不在（手工清过库），
--    任务行照样读得出来、姓名那一格落空（界面显示「未指定任务人」），
--    比"这一行读不出来、整张看板打不开"好得多。
--    ⚠️ 不给这三列加外键：agent_user 只停用不删除（见 permissions-schema.sql），id 不会悬空；
--    而它属于权限域，加外键会把"任务记录能不能写"绑在另一个域的表结构上
--    （与 agent_id 那条同一条理由）。
--
--  【current_executor_id 的 NULL 是有含义的】
--    它回答"此刻球在谁手上"：有值 = 球在那个账号手上（三个"待确认"与「权限不足」是发布人、
--    「新发布」是任务人、「待验收」是发布人）；NULL = 球在 agent 手上（三个"已确认"与"执行中"）
--    或这件事已经结束（已完成没有执行人；已取消保持取消那一刻的值）。
--    于是"哪个 agent 在跑"由 agent_id 回答，两列各答一个问题，不挤在一列里。
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
--  ⚠️ **每一列都带 COMMENT**：字段含义写在 COMMENT 里（元数据里读得到），
--  一段设计取舍写在列上方 —— 理由不是字段含义，塞进元数据只会把字段名挤没。
--
--  【索引不在这里建】见 ai.agent.config.SchemaIndexInitializer 的 DEFINITIONS：
--  MySQL 8.0 不认 `CREATE INDEX IF NOT EXISTS`，写在脚本里会让第二次启动直接失败。
-- ============================================================================

-- 一次派给 Agent 的任务
CREATE TABLE IF NOT EXISTS agent_task (
  id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '任务 id',
  -- 发布人：指派出任务的人。**写入路径必填**（判据在 DefaultAgentTaskService#realUser ——
  -- 它要读账号表，所以不在实体里）。列本身 NOT NULL：没有发布人的任务没有主语
  publisher_id        BIGINT       NOT NULL COMMENT '发布人：指派出任务的人（agent_user.id）',
  -- 任务人：交付任务的人。**写入路径必填**（发布人 / 任务人都必须是 agent_user 里真实存在的用户，
  -- 判据同上），列本身允许 NULL：库里可能有任务人为空的行，而读侧把它显示成「未指定任务人」，
  -- 比"这一行读不出来，整张看板打不开"好得多
  assignee_id         BIGINT       NULL COMMENT '任务人：交付任务的人（agent_user.id，写入路径必填）；NULL 显示为「未指定任务人」',
  -- 任务描述：这次要做什么。它同时也是生成第一条确认项的输入
  description         TEXT         NOT NULL COMMENT '任务描述：这次要做什么（也是生成第一条确认项的输入）',
  -- 状态取值见头注的状态链，库里的值一律是枚举名（AgentTaskStatus）。
  -- ⚠️ 读侧对认不出的取值落在 NEW 而不是"某一个看起来正常的中间态"：
  -- 落成终态会让这一行从看板的活跃列里消失，而"卡片不见了"是最难发现的一种坏法
  status              VARCHAR(32)  NOT NULL DEFAULT 'NEW' COMMENT '任务状态：NEW / PENDING_REQUIREMENT / CONFIRMED_REQUIREMENT / PENDING_PLAN / CONFIRMED_PLAN / PENDING_AUTHORIZATION / INSUFFICIENT_PERMISSION / AUTHORIZED / EXECUTING / PENDING_ACCEPTANCE / COMPLETED / CANCELLED（认不出的取值落 NEW）',
  -- 当前执行人：这条任务现在流转到谁手里（agent_user.id）。
  -- NULL 是**有含义的**：球在 agent 手上（三个"已确认"与"执行中"），或者这件事已经结束
  -- （已完成没有执行人；已取消保持取消那一刻的值）—— 见头注那一段
  current_executor_id BIGINT       NULL COMMENT '当前执行人：球在谁手上（agent_user.id）；NULL = 球在 agent 手上或任务已结束',
  -- 任务当前步骤，从 1 开始：1 需求 / 2 计划 / 3 授权 / 4 执行 / 5 验收 / 6 完成。
  -- 新发布也记 1 —— 它表示"停在第 1 步还没开始"，而不是第 0 步（界面上没有第 0 步这个东西）
  current_step        INT          NOT NULL DEFAULT 1 COMMENT '任务当前步骤：1 需求 / 2 计划 / 3 授权 / 4 执行 / 5 验收 / 6 完成（新发布也记 1）',
  -- 任务交给哪个 Agent 应用。**是字符串**：应用 id 由那张注册表（另一个业务域）定义，
  -- 本仓只管引用它，所以既不做外键、也不假设它长得像数字
  -- （与 agent_run.intent / tenant_id 同一条规矩：归因键的取值由业务侧定义，数据库管不了它有多少个）
  --
  -- 【谁写它】发布时不写（发布请求体里没有 agentId）；平台第一次接手这条任务时
  -- 用 ai.agent.task.agent-id 写入（见 DefaultAgentTaskExecution#stampAgent）。
  -- 于是"交给哪个 Agent 应用"只有一个来源 —— 那个 id 是部署方才知道的东西，
  -- 让发布人打一个字符串等于把"它对不对"变成一个人的记忆
  agent_id            VARCHAR(128) NULL COMMENT '交给哪个 Agent 应用（应用注册表里的 id，字符串；不做外键；平台第一次接手时由 ai.agent.task.agent-id 写入）',
  -- 执行结果：执行那一步走完时登记（EXECUTING → 待验收）。
  -- ⚠️ 状态是 INSUFFICIENT_PERMISSION 时这一列放的是**权限判断的结论**（缺哪条目录前缀、怎么放行）——
  -- 两者共用一列，界面按状态换标签显示（见 AgentTaskPanel）
  result              TEXT         NULL COMMENT '执行结果：执行那一步走完时登记（EXECUTING → 待验收）；状态是 INSUFFICIENT_PERMISSION 时放的是权限判断的结论',
  -- 取消原因：用户取消时填写，与 status=CANCELLED 同一次写入。
  -- 允许 NULL 只为了非取消状态的那一行；取消时由写入路径强制非空（服务层唯一入口）
  cancel_reason       VARCHAR(512) NULL COMMENT '取消原因：与 status=CANCELLED 同一次写入（取消时写入路径强制非空）',
  created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (id)
) COMMENT='agent任务';

-- 一条确认项：任务在第几步问了什么、给了哪些选项、用户答了什么
CREATE TABLE IF NOT EXISTS agent_task_confirm (
  id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '确认项 id',
  task_id      BIGINT      NOT NULL COMMENT '属于哪条任务（agent_task.id）',
  -- 任务步骤：这一条是在任务的第几步提出来的（值来自 agent_task.current_step，
  -- 即"第 1/2/3 步"= 需求 / 计划 / 授权三种确认）。列轴与 agent_step.step_index 同名同义
  step_index   INT         NOT NULL COMMENT '任务步骤（1 需求 / 2 计划 / 3 授权，值来自 agent_task.current_step）',
  -- 类型：REQUIREMENT 需求确认 / PLAN 计划确认 / AUTHORIZATION 授权确认
  confirm_type VARCHAR(32) NOT NULL COMMENT '确认类型：REQUIREMENT 需求 / PLAN 计划 / AUTHORIZATION 授权',
  -- 确认事项描述：要用户确认的那件事
  description  TEXT        NULL COMMENT '确认事项描述：要用户确认的那件事',
  -- 选项集：JSON 数组，如 ["确认","需要补充"]。空/null = 只收自由回复
  option_set   TEXT        NULL COMMENT '选项集（JSON 数组，如 ["确认","需要补充"]）；空/null = 只收自由回复',
  -- 用户确认项：用户选的选项或自由回复；NULL = **还没答**。
  -- "空串"与 NULL 在这里是两件事：空串是一次"答了但什么都没写"，会被拒绝在写入路径上
  answer       TEXT        NULL COMMENT '用户答的内容（选的选项或自由回复）；NULL=还没答（空串=答了但什么都没写，写入路径拒绝）',
  created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (id),
  -- 同一步骤同一种类型只允许一条：并发推进时第二次插入被数据库拦下，
  -- 而不是在界面上出现两条一模一样的确认项（然后用户只能猜该答哪一条）
  CONSTRAINT uk_agent_task_confirm_step UNIQUE (task_id, step_index, confirm_type),
  -- CASCADE：任务没了，它的确认项就没有主语了（与 agent_user_role 同一条理由）
  CONSTRAINT fk_agent_task_confirm_task FOREIGN KEY (task_id) REFERENCES agent_task (id) ON DELETE CASCADE
) COMMENT='agent任务确认项';

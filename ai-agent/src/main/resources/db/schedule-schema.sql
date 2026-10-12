-- ============================================================================
--  agent 定时任务（应用管理区 · 定时任务）
--
--  【这张表要回答的问题】
--  一条"到点就派活"的规则：什么时候派（定时正则）、以谁的名义派（发布人）、
--  派给谁（任务人）、派什么活（任务描述）。
--  它自己不跑模型、不碰文件 —— 到点做的事只有一件：按这四列生成一条 agent 任务记录
--  （agent_task），后面那条链（判需求 → 拆任务点 → 判授权 → 执行 → 验收）与人工发布的
--  任务完全一样，所以"定时任务跑出来的活"在库里的形态与人发的没有区别。
--
--  【与 agent_task 的分工：这张表是"模板"，那张表是"实例"】
--  一次触发产生一条 agent_task，触发 N 次就有 N 条；而规则只有这一条。
--  于是"这个点为什么又派了一模一样的活"由这一行回答，"这次干得怎么样"由那些行回答。
--  ⚠️ 两者之间**没有外键**：agent_task 是过程数据（会被清理、会被归档），
--  而这条规则要长期留着；加了外键之后清一次历史任务就会连带删掉规则本身 ——
--  那种坏法是"规则凭空不见了"，且与"有人删了它"长得一样。
--  代价说清：**从一条 agent_task 反查不到它由哪条规则派出来**。
--  ⚠️ 所以那条线索放在 agent_task 那一侧：`agent_task.schedule_id`（由来）——
--  本表**不存**"这次是谁派来的"，两列合一列的话，"这条规则派过几条"就得去数一张会变的表。
--
--  【定时正则是"6 段式"（Spring 的格式），不是 Unix 的 5 段式】
--  依次是 秒 分 时 日 月 周，例：`0 0 9 * * MON-FRI` = 周一到周五每天 09:00:00。
--  多出的一段是**秒**：本项目要它是因为界面上有"每 N 秒"这一类规则，
--  而那在 5 段式里表达不了（最小粒度是分钟）。
--  ⚠️ **5 段式会被拒绝，不做"自动补一个 0 秒"**：实测 Spring 7.0.9 的
--  `CronExpression.parse` 只认 6 段（`0 0 9` 直接抛 "must consist of 6 fields"），
--  而"用户敲进去的那一句就是跑着的那一句"正是拒绝它的理由 ——
--  替用户补一段，等于让他面对的报错指向一个他没写过的表达式。
--  判据与那句报错文案在 ScheduleCron 里，只有那一处。
--  ⚠️ 「日」与「周」不能同时写具体值：Spring 的 CronExpression 对
--  `0 0 9 1 * MON` 这种写法**不报错**，它按"两者取或"解释 ——
--  也就是每月 1 号**和**每个周一都会跑，而写的人多半只想其中一个。
--  那是一个不报错的错，所以写入路径直接拒绝（判据见 ScheduleCron#validate）。
--
--  【发布人与任务人：两个都是真实账号的用户 id】
--  与 agent_task 同一条规矩（见 task-schema.sql）：存 id 不存姓名，姓名在读侧 LEFT JOIN
--  agent_user 取**当前**的写法（账号改名之后，历史记录指向的人不变、显示的名字跟着改）。
--  写入路径收的也是 id（`POST /api/agent-task-schedules` 的 publisherId / assigneeId）：
--  界面那两格是搜索选择器（GET /api/permissions/users/search），选完给出来的就是候选里的 id；
--  服务层再核一遍账号表，id 不在时当场 400。
--  ⚠️ 读侧一律 LEFT JOIN 而不是 INNER JOIN：账号那一行万一不在（手工清过库），
--  规则照样读得出来、姓名那一格落空（界面显示「用户#id」），比"整页打不开"好得多。
--  ⚠️ 也不加外键：agent_user 只停用不删除，id 不会悬空（与 agent_task 同一条理由）。
--
--  【为什么没有 delete 之外的"已取消"状态】
--  与 agent_task 刻意不同：那边一次派活是"一件事"，取消之后要留下"当时为什么取消"；
--  这边是**一条配置**，停用（enabled=0）就是它的"取消"—— 配置不需要保留"我曾经不生效过"。
--  ⚠️ 但**已经派出去的任务不随规则消失**：删掉规则之后，那些 agent_task 照样在
--  agent任务 那一页里（它们与这条规则之间本来就没有外键）。
--
--  【enabled 与"没配好"的关系】
--  新建默认启用（enabled=1）：界面上配一次规则就是为了让它跑起来，
--  默认停用会让"保存了却没反应"成为第一次使用的固定体验。
--  配错的后果由**保存时的校验**兜（表达式当场解析 + 显示未来 5 次触发时刻），
--  而不是靠"先建一条停用的、确认无误再启用"这种两步流程。
--
--  【写法贴 MySQL】
--  与 task-schema.sql 同族：AUTO_INCREMENT / TIMESTAMP DEFAULT CURRENT_TIMESTAMP /
--  ON UPDATE CURRENT_TIMESTAMP / 建表语句末尾的 COMMENT='…'。
--  ⚠️ 表注释只能写在 CREATE TABLE 末尾（`ALTER TABLE t COMMENT='…'` 只有 MySQL 认，H2 报 42001）。
--  ⚠️ 每一列都带 COMMENT：字段含义写在 COMMENT 里，一段设计取舍写在列上方。
--
--  【索引不在这里建】见 ai.agent.config.SchemaIndexInitializer 的 DEFINITIONS：
--  MySQL 8.0 不认 `CREATE INDEX IF NOT EXISTS`，写在脚本里会让第二次启动直接失败。
-- ============================================================================

-- 一条"到点就派活"的规则
CREATE TABLE IF NOT EXISTS agent_task_schedule (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '规则 id',
  -- 定时正则（6 段式，见头注）。原样存归一之后的那一份：
  -- 落库的是"会被调度线程真正执行的那一句"，而不是用户敲进来的那一句
  -- （归一去掉多余空格，所以 "0  0 9 * * *" 存下来是 "0 0 9 * * *"）
  cron           VARCHAR(128) NOT NULL COMMENT '定时正则：6 段式「秒 分 时 日 月 周」，例 0 0 9 * * MON-FRI（实测 Spring 7.0.9 只认 6 段，Unix 的 5 段式会被拒）',
  -- 发布人：这条规则以谁的名义派活（agent_user.id）。**必填**，写入路径核账号表
  publisher_id   BIGINT       NOT NULL COMMENT '发布人：以谁的名义派活（agent_user.id）',
  -- 任务人：每次触发产生的任务派给谁（agent_user.id）。**写入路径必填**
  assignee_id    BIGINT       NULL COMMENT '任务人：每次触发产生的任务派给谁（agent_user.id，写入路径必填）；NULL 显示为「未指定任务人」',
  -- 任务描述：每次触发生成的那条任务的描述。它也是那条任务判需求、拆任务点的输入，
  -- 所以"定时任务派出来的活说不清要干什么"这件事的根在这里，而不在任务那边
  description    TEXT         NOT NULL COMMENT '任务描述：每次触发生成的那条任务的描述（也是它判需求、拆任务点的输入）',
  -- 启用状态：1 启用 / 0 停用。停用 = 不在调度线程池里（那一格没有线程），不是"线程还在但不做事"
  enabled        TINYINT      NOT NULL DEFAULT 1 COMMENT '启用状态：1 启用 / 0 停用（停用的规则不在调度线程池里）',
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间（改规则或启停都会刷新它）',
  PRIMARY KEY (id)
) COMMENT='agent定时任务';

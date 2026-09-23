-- ============================================================================
--  Agent 技能（应用管理区 · skill管理）
--
--  【这张表要回答的问题】
--  一条"给 Agent 用的技能"长什么样（放在哪个文件夹、叫什么、干什么用、正文是什么），
--  以及**它是谁的**：拥有者 id 是这条记录唯一的归属凭据 ——
--  列表按它筛、改与删按它判权。
--
--  【四个内容列】
--    folder      文件夹目录（界面上的选项集，取值来自登录人角色的目录前缀，见下面一段）
--    name        技能名
--    description 这个技能是干什么的
--    content     技能正文
--
--  【folder 的取值从哪来 —— 它不是一个自建目录树】
--  界面上的「文件夹目录」是**登录人自己能用的那些目录前缀**：
--  按 agent_user_role 取他的角色 id，再按 agent_role_dir 取这些角色的目录前缀。
--  于是"能建在哪个文件夹下"与"能在哪个目录下干活"是同一份授权，只有一处判据
--  （写入路径 SkillService 拿这份选项集校验，不在客户端判）。
--  ⚠️ 库里存的是**前缀原文**（与 agent_role_dir.dir_prefix 同一条规矩）：
--  存归一化后的路径会让界面上显示出一个用户自己没有选过的字符串。
--  ⚠️ 角色上那条目录授权被改掉之后，已经写下的 skill 不会跟着变 ——
--  两者不是外键关系，这张表记的是"当时放在哪儿"。
--
--  【owner_id / last_modified_by 存的是用户 id，不是姓名】
--  与 agent_task 的三个"人"列同一条理由：存 id 才指向同一个人，而姓名可以改。
--  姓名由读侧 LEFT JOIN agent_user 取**当前**的写法。
--  ⚠️ 读侧一律 LEFT JOIN 而不是 INNER JOIN：账号那一行万一不在（手工清过库），
--  skill 行照样读得出来、姓名那一格落空（界面显示「未知用户」），
--  比"整张列表打不开"好得多。
--  ⚠️ 不给这两列加外键：账号只停用不删除（见 permissions-schema.sql），id 不会悬空；
--  而 skill 属于另一个业务域，加外键会把"技能能不能写"绑在权限域的表结构上
--  （与 agent_task 的三个用户 id 列同一条理由）。顺带：真删掉一个账号时，
--  CASCADE 会把他的技能一起带走，而 RESTRICT 会让"删账号"这件事报错 ——
--  两种都不是这张表想要的反应。
--
--  【last_modified_by 在新建时就写创建人】
--  它是"最后修改人"，而新建那次本身就是一次写入 —— 留 NULL 会让界面在
--  "还没人改过"与"改过但没记下来"之间无法区分，而这两件事在列表上长得一样。
--
--  【唯一键：同一个人的同一个文件夹下不许重名】
--  重名的两个技能在列表上除了正文完全一样，而"这次改的是哪一个"就只能靠猜。
--  顺序是 (owner_id, folder, name) 而不是 (folder, name)：不同的人可以在同一个
--  文件夹下各建一个同名技能 —— 那是两份互不相干的记录，不是冲突。
--  ⚠️ 三列相加 64 + 192 + 128 = 384 字符，是 768 字符（utf8mb4 下 3072 字节）的一半，
--  在 InnoDB 的索引长度上限之内（长度依据见 permissions-schema.sql 的头注）。
--
--  【为什么没有 delete 之外的软删列】
--  删除是一次显式的人工动作（界面上带确认），而"取消了再看一眼"那个需求
--  由界面上重新建一条就能满足；留一个 deleted 标志会让每一个查询都必须记得带上它，
--  而漏掉一处的表现是"删掉的技能又出现在列表里"。
--
--  【写法贴 MySQL】
--  与 permissions-schema.sql / task-schema.sql 同族：AUTO_INCREMENT /
--  TIMESTAMP DEFAULT CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP /
--  建表语句末尾的 COMMENT='…'。
--  ⚠️ 表注释只能写在 CREATE TABLE 末尾（`ALTER TABLE t COMMENT='…'` 只有 MySQL 认，H2 报 42001）。
--  ⚠️ **每一列都带 COMMENT**：字段含义写在 COMMENT 里（元数据里读得到），
--  一段设计取舍写在列上方 —— 理由不是字段含义，塞进元数据只会把字段名挤没。
--
--  【索引不在这里建】见 ai.agent.config.SchemaIndexInitializer 的 DEFINITIONS：
--  MySQL 8.0 不认 `CREATE INDEX IF NOT EXISTS`，写在脚本里会让第二次启动直接失败。
-- ============================================================================

-- 一条技能
CREATE TABLE IF NOT EXISTS agent_skill (
  id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '技能 id',
  -- 文件夹目录：界面上的选项集，取值是**这条记录拥有者**角色的目录前缀（agent_role_dir.dir_prefix）
  folder           VARCHAR(192) NOT NULL COMMENT '文件夹目录（拥有者角色的目录前缀原文；存放时是什么就存什么）',
  name             VARCHAR(128) NOT NULL COMMENT '技能名（同一个人的同一个文件夹下唯一）',
  -- 这个技能是干什么的。允许 NULL：一句话说不清时可以留空，而界面会把它显示成空
  description      VARCHAR(512) NULL COMMENT '这个技能是干什么的',
  -- 技能正文。TEXT 而不是 VARCHAR：正文长度不可预期（一段提示词、一份流程说明都可能）
  content          TEXT         NULL COMMENT '技能正文',
  -- 拥有者：创建这条技能的人（agent_user.id）。**新增时由服务端填登录人**，
  -- 不来自请求体 —— 让客户端传的话，任何人都能把记录写成别人的
  owner_id         BIGINT       NOT NULL COMMENT '拥有者（agent_user.id，新增时由服务端填登录人，不来自请求体）',
  -- 最后修改人（agent_user.id）。新建时等于 owner_id
  last_modified_by BIGINT       NULL COMMENT '最后修改人（agent_user.id，新建时等于 owner_id）；NULL 表示那一行没有记下来',
  created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (id),
  -- 唯一约束的名字显式写出来：不写时 MySQL 与 H2 各自生成一个，而并发插入撞唯一键时
  -- （SkillConflictException 那条路径）错误信息里会出现它
  CONSTRAINT uk_agent_skill_owner_folder_name UNIQUE (owner_id, folder, name)
) COMMENT='Agent 技能：放在哪个文件夹、叫什么、干什么用、正文是什么，以及它是谁的';

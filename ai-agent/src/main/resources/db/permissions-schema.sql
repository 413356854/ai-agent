-- ============================================================================
--  Agent 权限管理：用户 / 角色 / 角色能调哪些工具 / 角色能在哪些目录下干活
--
--  【这五张表要回答的问题】
--  在此之前，"谁能用 Agent、能用哪几把工具、能在哪个目录下改东西"没有任何落点：
--  `/api/agent/ask` 的调用方可以传任意工作区目录、任意权限档位，而工具白名单是
--  `ToolRegistry` 里**全部已注册**的那些。于是"给某个人开一部分权限"这件事
--  在系统里无处表达 —— 只能靠"别把界面给他看"。
--
--  【五张表怎么分工】
--    agent_user           一个账号（名字 + 口令散列）
--    agent_role           一个角色（名字是唯一键）
--    agent_user_role      账号 ↔ 角色（多对多；一个账号的全部权限 = 它所有角色的并集）
--    agent_role_tool      角色 → 允许调用的 agent tool name（白名单，一行一把工具）
--    agent_role_dir       角色 → 允许操作的工作区目录前缀（一行一个前缀）
--
--  【为什么"多对多 + 取并集"，而不是把权限直接挂在账号上】
--  因为要能回答"这次授权是谁给的"：加一个人到"只读检索"这个角色是一次授权，
--  而给某一个人的白名单单独加一把工具是**另一件事** —— 后者在事后复盘时
--  看到的是"这个人为什么有这把工具"，答案只能靠回忆。走角色之后，
--  权限的变化只发生在角色上，账号那边只剩"他属于哪几个角色"。
--
--  【为什么白名单是"一行一把工具"而不是一列 CSV】
--  一列 CSV（`read_section,propose_review`）看着省一张表，代价是三条：
--    ① 判"这个人能不能调 read_section"要做字符串匹配 —— `read_section_v2` 会不会被
--       `read_section` 前缀命中？这类问题的答案取决于写匹配的人当时怎么想；
--    ② 唯一约束没了，同一把工具可以被写两遍，而"写了两遍"与"写了一遍"在权限上等价，
--       于是没人会发现；
--    ③ 撤销一把工具要为每一行做 CSV 编辑，而编辑失败的形态是**多撤了一把**。
--  一行一把工具 + 复合主键，把这三条全交给数据库。
--
--  【为什么目录前缀也单独一张表，理由同上一段】
--  前缀判定的判据在 Java 里只有一处（`RoleDirectory.covers`），库里只存事实。
--
--  【写法贴 MySQL】
--  与 agent-schema.sql 同族：AUTO_INCREMENT / TIMESTAMP DEFAULT CURRENT_TIMESTAMP /
--  ON UPDATE CURRENT_TIMESTAMP / 外键 ON DELETE CASCADE / 建表语句末尾的 COMMENT='…'。
--  H2 2.3.232 在 `MODE=MySQL` 下接受这几种写法（本文件被那条 H2 兼容性探针整份跑过）。
--  ⚠️ **表注释只能写在 `CREATE TABLE` 末尾**：`ALTER TABLE t COMMENT='…'` 只有 MySQL 认，
--  H2 报 42001（实测）。所以本文件里没有一条 ALTER，改注释就是改建表语句 + 重建表。
--  ⚠️ **每一列都带 COMMENT**：字段含义写在 COMMENT 里（元数据里读得到），
--  一段设计取舍写在列上方 —— 理由不是字段含义，塞进元数据只会把字段名挤没。
--
--  【长度上限的依据】
--  所有带唯一约束 / 主键的字符串列都 ≤ 192 字符：MySQL 的 InnoDB 默认
--  `innodb_large_prefix` 下，utf8mb4 索引最长 3072 字节 = 768 字符，
--  但**联合主键的两列相加**才是那个索引，留出余量之后 192 是安全值
--  （同样的取值见 agent_run 那些 VARCHAR(64) 的归因列）。
-- ============================================================================

-- 一个账号
CREATE TABLE IF NOT EXISTS agent_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '账号 id',
  -- 登录名。它是**唯一键**：同名账号会让"我是谁"这件事失去答案
  name          VARCHAR(64)  NOT NULL COMMENT '登录名（唯一，且不可改）',
  -- 口令的散列，格式见 ai.agent.entity.agent.permission.PasswordHash（算法 + 参数 + salt + 散列）。
  --
  -- ⚠️ 这一列**永远不存明文**，而且列名刻意叫 hash 不叫 password：
  -- 一个叫 password 的列，早晚会有人往里写明文（因为那样"先跑起来"最省事）。
  -- 长度 200 的依据：PBKDF2 的编码形态约 80 字符，留出将来换算法/加参数的余量。
  password_hash VARCHAR(200) NOT NULL COMMENT '口令散列（算法 + 参数 + salt + 散列，格式见 PasswordHash；**永远不存明文**）',
  -- 界面上显示的那个名字（「张三」）。与 name（登录名）分开：登录名要唯一且不能改，
  -- 显示名可以重名、可以改 —— 合成一列会让"改个显示名"变成一次登录名变更
  display_name  VARCHAR(128) NULL COMMENT '界面显示名（可重名、可改）',
  -- ENABLED / DISABLED。**不做删除**：删掉一个账号会让它的授权历史一起消失，
  -- 而"这个人当时是什么权限"正是复盘时要回答的问题。停用保留历史、且立刻生效。
  status        VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED / DISABLED（停用而非删除，授权历史要留住）',
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (id),
  -- 唯一约束的名字显式写出来：不写时 MySQL 与 H2 各自生成一个，
  -- 而并发插入撞唯一键时（`PermissionConflictException` 那条路径）错误信息里会出现它
  CONSTRAINT uk_agent_user_name UNIQUE (name)
) COMMENT='账号';

-- 一个角色
CREATE TABLE IF NOT EXISTS agent_role (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '角色 id',
  -- 角色名称。同样唯一：两个都叫「只读检索」的角色，界面上一模一样，
  -- 而它们各自挂的工具可以不同 —— 那是一次无法解释的权限差异
  name         VARCHAR(64)  NOT NULL COMMENT '角色名称（唯一）',
  -- 这个角色是干什么的（给三个月后看这行数据的人）
  description  VARCHAR(512) NULL COMMENT '这个角色是干什么的',
  -- 工具范围：LISTED / ALL（PermissionAccess）。
  --
  -- 【为什么它必须是一列，而不是"工具清单非空即白名单"】
  -- 用清单去表达"全部工具"会让"清单为空"同时意味着两件相反的事
  -- （没有工具 / 全部工具），而更早失效的是安全的那一边：
  -- 新建一个角色、还没来得及配工具时，它已经是"全部工具"了。
  --
  -- 【列名为什么叫 access_scope 而不是 access】
  -- `ACCESS` 是 SQL 标准与 MySQL 里都存在的关键词（用于锁表语句）。
  -- 用关键词当列名在一种库上能过、在另一种上报语法错，而报错的位置在 SQL 解析层，
  -- 与应用代码隔得很远。
  access_scope VARCHAR(16)  NOT NULL DEFAULT 'LISTED' COMMENT '工具范围：LISTED（按白名单）/ ALL（全部工具）',
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (id),
  CONSTRAINT uk_agent_role_name UNIQUE (name)
) COMMENT='角色';

-- 账号 ↔ 角色。一个账号可以有几个角色，它的权限是这些角色的**并集**
CREATE TABLE IF NOT EXISTS agent_user_role (
  user_id    BIGINT    NOT NULL COMMENT '账号 id（agent_user.id）',
  role_id    BIGINT    NOT NULL COMMENT '角色 id（agent_role.id）',
  -- 谁授的（审计：一次越权授权要能倒查到操作人）
  granted_by VARCHAR(128) NULL COMMENT '谁授的（审计：一次越权授权要能倒查到操作人）',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  -- 复合主键 = "同一个账号同一个角色只能有一行"。靠"先查再插"去重会在并发下写出两行，
  -- 而两行在界面上看起来只是"这条出现了两次"，没人会去查原因
  PRIMARY KEY (user_id, role_id),
  -- CASCADE：账号或角色没了，这条关系就没有主语了。
  -- 留着一个指向不存在账号的授权行，会让"这个角色有几个人"这个数偏大，
  -- 而偏大的方向是**看起来更危险**，于是没人会去质疑它 —— 静默的错数字
  CONSTRAINT fk_agent_user_role_user FOREIGN KEY (user_id) REFERENCES agent_user (id) ON DELETE CASCADE,
  CONSTRAINT fk_agent_user_role_role FOREIGN KEY (role_id) REFERENCES agent_role (id) ON DELETE CASCADE
) COMMENT='账号角色关联';

-- 角色 → 允许调用的工具名（白名单）
CREATE TABLE IF NOT EXISTS agent_role_tool (
  role_id    BIGINT       NOT NULL COMMENT '角色 id（agent_role.id）',
  -- tool_name 的取值来自 AgentTool.name()（白名单键，见 ToolRegistry）。
  -- 写错一个字母的后果是"这把工具永远调不到"，而那种失败不报错 ——
  -- 所以校验放在写入路径上：PermissionService 拿 ToolRegistry 逐个对，
  -- 认不出的名字当场 400，而不是安静地存进去
  tool_name  VARCHAR(128) NOT NULL COMMENT '允许调用的工具名（AgentTool.name()，写入时对着 ToolRegistry 校验）',
  -- 为什么给这个角色开这把工具
  note       VARCHAR(512) NULL COMMENT '为什么给这个角色开这把工具',
  created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  PRIMARY KEY (role_id, tool_name),
  CONSTRAINT fk_agent_role_tool_role FOREIGN KEY (role_id) REFERENCES agent_role (id) ON DELETE CASCADE
) COMMENT='角色工具白名单';

-- 角色 → 允许操作的工作区目录前缀
CREATE TABLE IF NOT EXISTS agent_role_dir (
  role_id    BIGINT       NOT NULL COMMENT '角色 id（agent_role.id）',
  -- 工作区目录前缀（形如 D:\work\demo 或 /srv/agent/demo）。
  -- 存的是**原始写法**，判定时才归一成绝对路径 —— 存归一化后的路径会让界面上
  -- 显示出一个用户自己没有输入过的字符串，而"我明明填的是这个"是最常见的争执
  --
  -- `%` 与 `_` 在这张表里是**普通字符**，不做 LIKE 匹配（那会让一个目录名里的
  -- 下划线变成通配符）。判定在 Java 里按路径分段比，见 RoleDirectory
  dir_prefix VARCHAR(192) NOT NULL COMMENT '允许操作的工作区目录前缀（存原始写法；% 与 _ 是普通字符，按路径分段比）',
  note       VARCHAR(512) NULL COMMENT '为什么给这个角色开这个目录',
  created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  PRIMARY KEY (role_id, dir_prefix),
  CONSTRAINT fk_agent_role_dir_role FOREIGN KEY (role_id) REFERENCES agent_role (id) ON DELETE CASCADE
) COMMENT='角色目录前缀';

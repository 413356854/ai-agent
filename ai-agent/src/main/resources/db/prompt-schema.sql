
-- ============================================================================
--  Prompt 版本管理（阶段四 · 工程化第 3 件）
--
--  【要解决的问题】
--  在此之前，提示词是 classpath 资源（prompts/*.st）：改一个字就要重新打包、重启。
--  而提示词的改动有三个特点，让它必须像代码一样被管理：
--    ① **改坏了不报错** —— 模型照样回答，只是答得不对（这是本文件存在的主要理由）；
--    ② **要能回滚** —— "上一版更好"是最常见的一句话，而它必须能在一分钟内变成事实；
--    ③ **要能回答"当时用的是哪一版"** —— 否则事后复盘只能靠回忆。
--
--  【为什么落库而不是放文件 / 放配置中心】
--  落库换来的东西只有一样：**不可变的历史**。文件能被覆盖（覆盖之后没有痕迹），
--  而这张表只允许追加（见 PromptStore 接口注释里为什么没有 update 与 delete）。
--  真上生产时这一层可以换成配置中心，但"只追加"这条纪律要一起搬过去。
--
--  【为什么留 active 指针表，而不是在 prompt_version 上加一列 is_active】
--  加一列的写法有一个很难发现的失效形态：切换时"先把旧的置 0、再把新的置 1"，
--  两步之间任何一次查询都会读到**一份都不生效**的状态。两份提示词各有一个指针、
--  切换是一行 UPDATE，就没有这个中间态。顺带一个好处：指针表可以顺便记
--  **是谁在什么时候切的**（prompt_version 只记"这版是谁写的"）。
--
--  【为什么把 token 估算一起存下来】
--  同一个估算器对同一段文本的结果是确定的（纯函数），所以存下来不会出现
--  "两次读到的数不一样"。而它有真实用处：换提示词最常见的副作用是**预算变了**，
--  页面上要能直接看出"这一版比上一版多占多少 token" ——
--  不然那个变化只在"历史被裁得更早"时才显形，而那时没人会想到是提示词改的。
-- ============================================================================

-- 每一版提示词的正文（只追加，不修改、不删除）
CREATE TABLE IF NOT EXISTS prompt_version (
  kind         VARCHAR(64)   NOT NULL,              -- PromptKind.defaultValue（system-agent / system-agent-tools）
  version      INT           NOT NULL,              -- 每份提示词各自从 1 开始
  content      TEXT          NOT NULL,              -- 正文
  hash         VARCHAR(32)   NOT NULL,              -- SHA-256 前 12 位：用于"页面这一版 == 库里那一版"的断言
  char_count   INT           NOT NULL DEFAULT 0,
  token_estimate INT         NOT NULL DEFAULT 0,    -- 估算 token（换提示词最常见的副作用是预算变了）
  note         VARCHAR(512)  NULL,                  -- 这一版为什么这么改（必填，见 PromptValidator）
  author       VARCHAR(128)  NULL,
  -- 复制自哪一版。首次手工新建为 NULL；**回滚也记它** ——
  -- 于是一条回滚在历史里长成"第 7 版（复制自第 3 版）"，而不是"指针莫名其妙退回去了"
  forked_from  INT           NULL,
  created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (kind, version)
);

-- 当前生效的那一版（每份提示词一行）
CREATE TABLE IF NOT EXISTS prompt_active (
  kind          VARCHAR(64) NOT NULL,
  version       INT         NOT NULL,
  -- 谁把它切成这一版的。与 prompt_version.author 是两个不同的问题：
  -- 「这一版是谁写的」与「是谁让它生效的」在灰度/回滚时经常不是同一个人
  activated_by  VARCHAR(128) NULL,
  activated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (kind)
);

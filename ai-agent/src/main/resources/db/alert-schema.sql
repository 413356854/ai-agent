-- ============================================================================
--  异常告警 + 灰度发布（阶段四 · 工程化第 5 件）
--
--  【这一件要解决的问题】
--  验收口径写的是「成本异常**有人能发现**」。在此之前那句话只做到了一半：
--  成本看板能**看见**异常（notes 里那几条 ⚠️），但"看见"的前提是有人打开那一页 ——
--  而它的失败方式恰好是最安静的一种：**没有人被通知**。
--  所以这一件的第一步是把那些"人读的 ⚠️"变成**可查询、可确认、会自己出现和消失的事件**。
--
--  【为什么判据不在这两张表里】
--  因为没有一条 SQL 能回答"什么算异常"：它是一条业务判断
--  （"连续 3 次检索空命中"里的那个 3、"灰度偏 30%"里的那个 30%）。
--  判据写在 `AlertRule` 枚举里，这里只存**它的结论**。
--  这与成本看板"聚合放 SQL、口径放代码"是同一条分界。
--
--  【为什么一张表存状态，而不是一张表存事件流水】
--  见 AlertEvent 的注释：一个持续存在的异常会在每次评估时都产生一条记录，
--  一天 288 次评估就是 288 条一样的东西，而真正的新问题被埋在中间。
--  所以这里是"一条判据的一个主体一行"，靠 fingerprint 去重，
--  持续成立只更新 last_seen_at / occurrences。
--  ⚠️ 但"什么时候开始的、什么时候结束的"必须留住 —— 那是复盘时的第一句话，
--  所以 first_seen_at / resolved_at 两列不能省。
-- ============================================================================

-- 告警的当前状态（一条判据 × 一个主体 = 一行）
CREATE TABLE IF NOT EXISTS alert_event (
  -- 去重键：rule_code + '|' + subject。**它是主键** ——
  -- 靠"先查再写"去重会在并发评估下写出两行，而那种重复在界面上看起来
  -- 只是"同一个问题出现了两遍"，没人会去查原因
  fingerprint    VARCHAR(160) NOT NULL,
  rule_code      VARCHAR(64)  NOT NULL,             -- AlertRule.code
  subject        VARCHAR(128) NOT NULL DEFAULT '',  -- 说的是谁（模型名 / 灰度版本 / 空=没有主体）
  severity       VARCHAR(16)  NOT NULL,             -- INFO / WARNING / BLOCKER
  phase          VARCHAR(16)  NOT NULL,             -- GATE / OBSERVE
  state          VARCHAR(16)  NOT NULL,             -- OPEN / ACKNOWLEDGED / RESOLVED
  first_seen_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 最后一次发现它成立。FIRING 时它≈现在；RESOLVED 之后它停住不动 ——
  -- 这两个时间合起来才回答得了"它到底存在了多久"
  last_seen_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  occurrences    INT          NOT NULL DEFAULT 1,   -- 一共被发现了多少次（持续成立会一直涨）
  resolved_at    TIMESTAMP    NULL,                 -- 恢复时间；NULL = 还在响
  acknowledged_at TIMESTAMP   NULL,                 -- 人看过的时间；NULL = 还没人看
  acknowledged_by VARCHAR(128) NULL,
  observed       VARCHAR(512) NULL,                 -- 观测值（例如 "0.0500（3/60）"）
  threshold      VARCHAR(512) NULL,                 -- 判据阈值 + 它的形态
  why            VARCHAR(1024) NULL,                -- 为什么这个阈值是这个数
  action         TEXT         NULL,                 -- 该做什么（来自判据目录）
  window_label   VARCHAR(64)  NULL,                 -- 判定时的窗口（没有它，数字不可复现）
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (fingerprint)
);

-- ============================================================================
--  灰度发布（提示词新版的按比例放量）
--
--  【为什么灰度的对象是"提示词版本"】
--  这一件与第 3 件（Prompt 版本管理）是同一根链条上的两段：
--  第 3 件让"改哪一版、能不能回滚、当时用的是哪一版"成为事实，
--  而它**答不了"哪一版更好"** —— 那需要分组流量。
--  灰度发布就是那条缺失的链路：让新版本只作用于一部分运行，于是两组之间有得比。
--
--  【★ 为什么只有一张配置表，没有"运行→分组"的映射表】
--  因为分流必须是**算出来的**，不能是**存下来的**：
--  按运行 id 取稳定哈希 → 分桶 → 与重量比。同一份运行 id 永远落同一个桶，
--  所以不需要一张映射表，也就不存在"映射表与配置不一致"这个状态。
--  存映射表还会带来一个更坏的后果：它让"这次走的是新版"变成一个可以被改写的字段，
--  而分组一旦能被改写，"两组对比"就失去了意义。
--
--  【为什么停止灰度要写 ended_at 而不是删除这一行】
--  与 prompt_version 只追加同一条纪律：一次灰度是一次实验，
--  事后必须能回答"它什么时候开的、开了多久、当时配的重量是多少"。
--  删掉的话，那些走了候选版本的运行在库里就成了"不知道为什么用了那一版"。
-- ============================================================================

CREATE TABLE IF NOT EXISTS prompt_canary (
  kind              VARCHAR(64) NOT NULL,            -- PromptKind.defaultValue
  candidate_version INT         NOT NULL,            -- 候选版本号（**必须已存在**，见 CanaryService.start）
  weight_percent    INT         NOT NULL,            -- 放量比例（1..99）
  started_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  started_by        VARCHAR(128) NULL,
  note              VARCHAR(512) NULL,               -- 这次实验想验证什么（必填）
  ended_at          TIMESTAMP   NULL,                -- 停止时间；NULL = 进行中
  ended_by          VARCHAR(128) NULL,
  end_reason        VARCHAR(512) NULL,               -- 为什么停（推全 / 回滚 / 实验结束）
  PRIMARY KEY (kind)
);

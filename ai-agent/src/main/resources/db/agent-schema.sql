-- ============================================================================
--  Agent 运行记录（阶段三：状态持久化 + 断点恢复）
--
--  目标：进程被杀之后**从检查点恢复**，而不是把已经查过的工具与花掉的 token 全丢掉。
--  路线文档的验收原话是「进程被杀能从检查点恢复」。
--
--  【写法刻意贴 MySQL】
--  连接串带 MODE=MySQL，这份 DDL 用 MySQL 的写法（AUTO_INCREMENT / TEXT /
--  ON UPDATE CURRENT_TIMESTAMP）。实测已确认 H2 2.3.232 在兼容模式下接受这几种写法
--  （见 store 包的诊断测试）。这样将来换真 MySQL 时，DDL 基本不用改。
--
--  ⚠️ 一个实测发现的兼容性边界：MySQL 的**前缀索引**（`INDEX (col(64))`）在
--  H2 的 MODE=MySQL 下**不被接受**。所以这里不给 TEXT 列建前缀索引 ——
--  本场景也不需要（按主键查，不按正文查）。
-- ============================================================================

-- 一次 Agent 运行（一个用户问题）
CREATE TABLE IF NOT EXISTS agent_run (
  id            VARCHAR(64)  NOT NULL,               -- 运行 id（恢复时的对账凭据）
  question      TEXT         NOT NULL,               -- 用户原始问题
  status        VARCHAR(32)  NOT NULL,               -- RUNNING / ANSWERED / FAILED / INTERRUPTED
  stop_reason   VARCHAR(32)  NULL,                   -- 走到哪个出口（AgentStopReason）
  final_answer  TEXT         NULL,                   -- 最终答案；被上限截断时为 NULL
  total_tokens  INT          NOT NULL DEFAULT 0,     -- 累计 token（阶段四成本看板的运行时口径）
  tool_calls    INT          NOT NULL DEFAULT 0,     -- 一共提出过几次工具调用（含被拒的）
  elapsed_ms    BIGINT       NOT NULL DEFAULT 0,
  node          VARCHAR(128) NULL,                   -- 哪个实例跑的（多实例时能看出"是谁挂的"）
  error         TEXT         NULL,                   -- 失败原因（status=FAILED 时）
  -- 失败细分原因（第七轮新增）：MODEL_ERROR 这一个出口下面藏着鉴权失败/限流/超时/连不上/
  -- 以及**我们自己的并发闸门拒绝**，而"太挤"与"上游挂了"的排查方向完全相反。
  -- 老数据为 NULL —— 加列不影响已有行，这正是这个设计的好处
  failure_cause VARCHAR(32)  NULL,
  -- 【阶段四成本看板】业务归因键（路线文档的「成本归因：按业务/租户/功能拆分」）。
  -- 由调用方在 /ask 时给出，本列**刻意不做外键、不做枚举**：
  -- 归因键是业务侧的概念，数据库管不了它有多少取值。
  -- NULL 表示"这次运行没有标注归因" —— 看板会把它显示成「未标注」，
  -- 而不是悄悄并进某一个业务里（那会让那一项虚高，且无从发现）
  intent        VARCHAR(64)  NULL,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

-- 每一步：一次模型往返（可能附带若干工具调用）
--
-- 【transcript 这一列是断点恢复的关键】
-- 它存的是**这一步结束时**完整的对话消息列表（JSON）。
-- 没有它就只能"从头重放"，而那会把已经执行过的工具再执行一遍 ——
-- 对于只读工具是浪费，对于写操作是灾难（这就是路线文档说的"工具调用重试会不会产生重复副作用"）。
CREATE TABLE IF NOT EXISTS agent_step (
  run_id            VARCHAR(64) NOT NULL,
  step_index        INT         NOT NULL,            -- 从 1 开始
  thought_text      TEXT        NULL,                -- 模型这一步的文字输出（ReAct 的 think）
  prompt_tokens     INT         NOT NULL DEFAULT 0,
  completion_tokens INT         NOT NULL DEFAULT 0,
  -- 【阶段四成本看板】这一步的输入里有多少命中了上游的提示词缓存。
  -- 为什么必须单独一列：缓存命中与未命中的**单价差一个数量级**（DeepSeek 官方口径是 50 倍），
  -- 而 prompt_tokens 是**两者之和**。只存合计的话，成本的误差方向永远是"高估"，
  -- 且高估的倍数取决于上游缓存命中率 —— 一个我们既看不见也调不了的量。
  -- 上游没报这个字段时记 0，读的时候按"全部未命中"算（偏保守，见 CostPricing 的注释）。
  cache_read_tokens INT         NOT NULL DEFAULT 0,
  -- 【阶段四成本看板】这一步是**哪个模型**跑的。
  -- 为什么落在"每一步"而不是"每次运行"上：成本和 token 一样，是**每一步**的产物
  -- （同一轮运行里换了模型，每步的单价就不同）。落在运行上只有在
  -- "整个运行只用一个模型"时才成立，而那正是灰度/切换期间不成立的前提。
  -- 上游没报 / 取不到名字时为空串，看板显示成「未知模型」，且**不给它算命**
  model_name        VARCHAR(128) NOT NULL DEFAULT '',
  elapsed_ms        BIGINT      NOT NULL DEFAULT 0,
  transcript        TEXT        NOT NULL,            -- 这一步之后的完整消息列表（JSON）
  created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (run_id, step_index)
);

-- 每一次工具调用意图的落库（含被拒绝的）
--
-- 【为什么要存 **被拒绝** 的那些】
-- 报告里要能回答"模型一共想调几次、其中几次没让它调"。
-- 只记成功的会让这个数字永远偏小，而这正是踩坑库 #56 记的那个坑。
CREATE TABLE IF NOT EXISTS agent_tool_invocation (
  run_id       VARCHAR(64) NOT NULL,
  step_index   INT         NOT NULL,
  call_index   INT         NOT NULL,                 -- 同一步内的第几个调用（并行工具调用会有多个）
  call_id      VARCHAR(128) NULL,                    -- 模型给的 id（回填时对账用）
  tool_name    VARCHAR(128) NOT NULL,
  arguments    TEXT        NULL,                     -- 原始 JSON 参数（截断后）
  stage        VARCHAR(32) NOT NULL,                 -- UNKNOWN_TOOL / PARSE_ERROR / VALIDATION_ERROR / EXECUTION_ERROR / OK
  feedback     TEXT        NULL,                     -- 回填给模型的文本
  elapsed_ms   BIGINT      NOT NULL DEFAULT 0,
  created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (run_id, step_index, call_index)
);

-- 恢复时最常见的查询是"这个实例上还有哪些没跑完的运行"
CREATE INDEX IF NOT EXISTS idx_agent_run_status ON agent_run (status, updated_at);

-- 【阶段四成本看板】看板的每一次查询都带一个时间窗（默认最近 7 天），
-- 且按天分桶。所以这个索引是"看板能不能在数据量上来之后还跑得动"的落点 ——
-- 没有它，看板会随运行数线性变慢，而现象是"这个页面越来越慢"，看不出是缺索引。
CREATE INDEX IF NOT EXISTS idx_agent_run_created ON agent_run (created_at);

-- ============================================================================
--  人工审批记录（阶段三第 9 件：HITL）
--
--  【它为什么必须落库，而不是只放在内存里】
--  内存里的审批状态够用（一个 future 就能让阻塞的虚拟线程醒来），
--  但"够用"与"能审计"是两件事，而 HITL 的全部价值在后一半：
--    ① 路线文档的验收要能回答「**什么时候让人确认**」—— 没有记录时这句话只能靠回忆；
--    ② 写操作出事之后的第一个问题是"**这是谁批的**"，而那时内存里那个 future 早就不在了；
--    ③ 审批记录是**唯一能证明"闸门真的拦下过东西"**的证据。一张空表既可能表示
--       "从没有过危险操作"，也可能表示"闸门没装" —— 这正好是踩坑 #67 的形状。
--
--  【状态取值】PENDING / APPROVED / DENIED / TIMED_OUT / EXPIRED
--  后两个刻意与 DENIED 分开：它们对执行是同一个结果（不执行），
--  但要通知的人完全不同 —— "人不批准"是业务判断，"没人搭理"是流程没接上。
--
--  【decided_* 三列为空表示"还没人处理"】
--  TIMED_OUT / EXPIRED 时 decided_by 也是 NULL：**它们没有"决定人"** ——
--  写一个 "system" 进去会让"谁批的"这个问题得到一个是又不是的答案。
-- ============================================================================
CREATE TABLE IF NOT EXISTS agent_tool_approval (
  id            VARCHAR(64)  NOT NULL,               -- 审批 id（界面拿它批准/拒绝）
  run_id        VARCHAR(64)  NULL,                   -- 属于哪次运行；外部 MCP 链路的调用为 NULL
  step_index    INT          NULL,                   -- 第几步（审计要能回答"它挂在哪一步"）
  tool_name     VARCHAR(128) NOT NULL,
  arguments     TEXT         NULL,                   -- **真正会被执行的那份参数**（人批的就是它）
  hints         VARCHAR(128) NULL,                   -- 风险声明的可读描述（展示用，故存字符串）
  summary       VARCHAR(512) NULL,                   -- 工具自己的一句话摘要
  status        VARCHAR(32)  NOT NULL,               -- PENDING / APPROVED / DENIED / TIMED_OUT / EXPIRED
  requested_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decided_at    TIMESTAMP    NULL,
  decided_by    VARCHAR(128) NULL,                   -- 谁做的决定（超时/作废时为空）
  decision_note TEXT         NULL,                   -- 决定说明（拒绝理由会回填给模型）
  PRIMARY KEY (id)
);

-- 待办列表的查询是"所有还没处理的，先来的在前"
CREATE INDEX IF NOT EXISTS idx_agent_tool_approval_status ON agent_tool_approval (status, requested_at);

-- 运行详情里要按 run 取全部审批记录
CREATE INDEX IF NOT EXISTS idx_agent_tool_approval_run ON agent_tool_approval (run_id, requested_at);

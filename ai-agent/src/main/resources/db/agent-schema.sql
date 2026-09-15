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
  total_tokens  INT          NOT NULL DEFAULT 0,     -- 累计 token（阶段四成本看板的数据源）
  tool_calls    INT          NOT NULL DEFAULT 0,     -- 一共提出过几次工具调用（含被拒的）
  elapsed_ms    BIGINT       NOT NULL DEFAULT 0,
  node          VARCHAR(128) NULL,                   -- 哪个实例跑的（多实例时能看出"是谁挂的"）
  error         TEXT         NULL,                   -- 失败原因（status=FAILED 时）
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

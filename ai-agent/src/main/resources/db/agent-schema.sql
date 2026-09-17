-- ============================================================================
--  Agent 运行记录（阶段三：状态持久化 + 断点恢复）
--
--  目标：进程被杀之后**从检查点恢复**，而不是把已经查过的工具与花掉的 token 全丢掉。
--  验收原话是「进程被杀能从检查点恢复」。
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
  -- 【阶段四成本看板】业务归因键（「成本归因：按业务/租户/功能拆分」）。
  -- 由调用方在 /ask 时给出，本列**刻意不做外键、不做枚举**：
  -- 归因键是业务侧的概念，数据库管不了它有多少取值。
  -- NULL 表示"这次运行没有标注归因" —— 看板会把它显示成「未标注」，
  -- 而不是悄悄并进某一个业务里（那会让那一项虚高，且无从发现）
  intent        VARCHAR(64)  NULL,
  -- 【阶段四 Agent trace】**会话 id**：把"同一次对话里的多次运行"串起来（trace 五层的第一层）。
  --
  -- 【它与 id 的分工，别混】
  --   id            = 一次运行（一个用户问题），恢复与对账的凭据；
  --   conversation_id = 一段对话（可能包含多个问题、多次运行）。
  --
  -- 为什么必须真的加一列，而不是"把同一段对话里的问题拼成一个 run"：
  -- ① 一次运行有唯一的状态、停止原因、检查点与成本，把多个问题塞进一个 run
  --    会让"这次运行成功了吗"这个问题失去答案（三个问题里两个成了、一个失败）；
  -- ② 断点恢复的粒度就是"一次运行"，改大之后恢复会带着别人的问题一起重跑。
  -- 所以是"多次运行 → 一个会话"，而不是"一个问题 = 一个会话"。
  --
  -- NULL / 空串 = **没有会话信息**（curl 直接调的、外部系统调的）。
  -- 刻意不自动生成一个：给每次孤立调用编一个会话 id，会让"有会话"这件事
  -- 看起来处处成立，而真相是"我们不知道这些运行属不属于同一段对话" ——
  -- 那与成本看板"没标注就显示未标注"是同一条规矩。
  conversation_id VARCHAR(64) NULL,
  -- 【阶段四第 6 件 · 成本归因】**租户**：这次运行花的是谁的钱。
  --
  -- 【为什么必须与 intent 分开成两列，而不是"按业务/租户"共用一个字段】
  -- 这是这一件最要紧的一个决定。验收口径是「成本归因（按业务/租户/功能拆分）」——
  -- 读起来像"一个字段存三个维度"，但那样做的后果是具体的：
  --   · 拼字符串（`京东-客服问答`）之后，"按业务看一共多少"要靠 LIKE 前缀匹配，
  --     而一次拼写不一致（`京东 / 客服`）就会让同一件事裂成两行；
  --   · 更糟的是**维度互相污染**：一个租户想改名、一个问题想换业务归属，
  --     都要去改历史数据（而那正是"历史只追加"要避免的事）。
  -- 所以是两列、两个维度、两个 GROUP BY：`tenant_id` 回答"谁在花钱"，
  -- `intent` 回答"花在哪个功能上"。同一个租户 + 不同功能 = 两行，这是对的。
  --
  -- NULL = **这次运行没有标注租户**（curl 直接调、外部系统调、界面没填）。
  -- 与 intent 同一条规矩：看板会把它显示成「未标注租户」并**独立成一行**，
  -- 而不是并进某个租户里（那会让那个租户的成本虚高，且无从发现）。
  tenant_id     VARCHAR(64)  NULL,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

-- 每一步：一次模型往返（可能附带若干工具调用）
--
-- 【transcript 这一列是断点恢复的关键】
-- 它存的是**这一步结束时**完整的对话消息列表（JSON）。
-- 没有它就只能"从头重放"，而那会把已经执行过的工具再执行一遍 ——
-- 对于只读工具是浪费，对于写操作是灾难（这就是"工具调用重试会不会产生重复副作用"）。
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
  -- 【阶段四 Agent trace】这一步用的是**哪一版系统提示词**（指纹形如 `v3:9ab12cd34ef5`）。
  --
  -- 【为什么必须落这一列，而且落的是指纹不是版本号】
  -- 提示词可以在两次运行之间换版本，而**报告里看不出来** ——
  -- 于是"某天的答案变差了"追不到"那天换了提示词"，只能靠回忆。
  --
  -- 存指纹（SHA-256 前 12 位）而不是只存版本号，是因为版本号是**指针**、指纹是**内容**：
  --   ① 版本号会被复用吗？不会（历史只追加）—— 但"第 3 版"在库被重建/迁移之后
  --      可能对应另一份正文，而指纹不会；
  --   ② 断言需要的是"发出去的确实是这一版的内容"。这正是阶段四第 3 件
  --      证明热更新时用的同一个判据 —— 那次是逐字节比对，
  --      这里把它**落成数据**，让"当时用的是哪一版"在事后也能回答。
  --
  -- 空串 = 库里读不到这一版（老数据、或提示词服务不可用），
  -- 与"某个叫空串的版本"不会混淆（后者不是一个合法的指纹）。
  prompt_fingerprint VARCHAR(64) NOT NULL DEFAULT '',
  -- 【阶段四第 5 件 · 灰度发布】这一步所在的那次运行属于**哪一组**。
  --
  -- 【为什么分组必须与指纹分开存，而且必须落库】
  -- 灰度实验要回答"新版比旧版好还是差"，而事后唯一能判断"某次运行属于哪一组"的
  -- 依据就是这一列。用指纹反推是不行的：指纹只说明"用的是哪一版的内容"，
  -- 而一次实验里的**对照组（stable）用的也是某一版内容** ——
  -- 更糟的是，实验结束后如果把候选版推全，"候选版"这个身份就消失了，
  -- 于是那次实验的两组在数据里再也分不开。
  --
  -- 取值：'canary'（走了候选版）/ 'stable'（对照）/ 空串（**没有参与这次实验**）。
  -- ⚠️ 空串与 'stable' 是两件事：前者是"这次实验不包含它"（灰度还没开），
  -- 后者是"它是这次实验的对照组"。混起来会让灰度报告的参与率虚高，
  -- 而参与率虚高的直接后果是"样本够了"这个判断过早成立。
  canary_variant    VARCHAR(16) NOT NULL DEFAULT '',
  elapsed_ms        BIGINT      NOT NULL DEFAULT 0,
  transcript        TEXT        NOT NULL,            -- 这一步之后的完整消息列表（JSON）
  created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (run_id, step_index)
);

-- 每一次工具调用意图的落库（含被拒绝的）
--
-- 【为什么要存 **被拒绝** 的那些】
-- 报告里要能回答"模型一共想调几次、其中几次没让它调"。
-- 只记成功的会让这个数字永远偏小。
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

-- 【阶段四 Agent trace】按会话把多次运行串起来读（trace 的第一层）。
-- 没有它，"这段对话里一共跑了几次、各自花了多少"只能全表扫 ——
-- 而那个查询恰恰是排查"用户说这个功能今天一直不对"时的第一个动作。
CREATE INDEX IF NOT EXISTS idx_agent_run_conversation ON agent_run (conversation_id, created_at);

-- 【阶段四第 6 件 · 成本归因】按租户看钱是看板最长的那条查询（多一个 GROUP BY 维度，
-- 而它的分组基数由**租户数**决定 —— 那是唯一会随业务增长而增长的维度）。
-- 索引列顺序刻意是 (tenant_id, created_at) 而不是反过来：查询形如
-- 「窗口内按租户分组」，所以先按租户收敛、再在组内按时间过滤，
-- 与 conversation 那条索引同一个形状。
CREATE INDEX IF NOT EXISTS idx_agent_run_tenant ON agent_run (tenant_id, created_at);

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
--    ① 验收要能回答「**什么时候让人确认**」—— 没有记录时这句话只能靠回忆；
--    ② 写操作出事之后的第一个问题是"**这是谁批的**"，而那时内存里那个 future 早就不在了；
--    ③ 审批记录是**唯一能证明"闸门真的拦下过东西"**的证据。一张空表既可能表示
--       "从没有过危险操作"，也可能表示"闸门没装" —— 这正好是「装上了」与「真在生效」是两件事。
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

-- ============================================================================
--  检索明细（阶段四第 2 件：调用日志 + Agent trace 五层里的**「检索」那一层**）
--
--  【为什么它必须单独一层，而不能继续藏在 agent_tool_invocation.feedback 里】
--  search_docs 的返回内容一直是**文本**（回填给模型的那段）。文本对模型是对的 ——
--  它要读的是"有没有相关内容"，不是字段名。但对**人**它什么都回答不了：
--    · "这次任务一共检索了几次" —— 要数 feedback 里出现了几次"在 516 个文档片段里找到"；
--    · "命中的是哪几个块" —— 要正则解析那段中文；
--    · "检索塞进 prompt 的 token 有多少" —— 文本里根本没有这个数。
--  也就是说，阶段二量出来的 token 口径（avgTokensUsed）在这条链路上**追不到**，
--  而验收要的恰恰是「日志能不能还原执行过程」。
--
--  【为什么不复用 agent_tool_invocation】
--  那条记录的主键是 (run_id, step_index, call_index) —— 它是**一次调用**的粒度。
--  而一次检索调用会有 N 条命中（N 默认 5、上限 10），把它们挤进那条记录只有两种下场：
--  ① 拼成 JSON 存进 feedback 旁边一列（那就等于把结构化数据退化成字符串，
--     正是这一层要消灭的东西）；② 把 feedback 顶爆（它有 max-chars 上限）。
--  所以是子表：调用一次 → N 行命中。
--
--  【三层的关系（这一层的坐标）】
--    会话 → 轮次/运行（agent_run，含 conversation_id）
--         → LLM 调用（agent_step，含 prompt_fingerprint）
--         → 工具调用（agent_tool_invocation）
--         → **检索命中（retrieval_hit）** ← 本表
--  前三层是"谁在什么上下文里做了什么"，本表是"它到底看到了什么"。
-- ============================================================================
CREATE TABLE IF NOT EXISTS retrieval_hit (
  run_id       VARCHAR(64)  NOT NULL,
  step_index   INT          NOT NULL,               -- 第几步发起的检索
  call_index   INT          NOT NULL,               -- 这一步里的第几次工具调用（与 agent_tool_invocation 同一套编号）
  hit_rank     INT          NOT NULL,               -- 第几名（从 1 开始，**就是模型看到的名次**）
  chunk_id     VARCHAR(256) NULL,                   -- 块 id（`文档id#序号`）—— 能直接回到分块结果
  document_id  VARCHAR(256) NULL,                   -- 来源文档
  breadcrumb   VARCHAR(512) NULL,                   -- 标题路径（"这条命中属于哪一节"）
  token_count  INT          NOT NULL DEFAULT 0,     -- 这个块**估算**的 token（进 prompt 的量级）
  -- 相关性得分。**只在同一次检索内可比** —— 不同检索引擎的量纲不同（这正是混合检索
  -- 必须按名次融合、不能直接相加的原因）。落它不是为了与别次比较，而是为了回答
  -- "第 3 条与第 4 条的分差有多大"（分差极小说明这次检索其实没区分度）
  score        DOUBLE       NOT NULL DEFAULT 0,
  excerpt      VARCHAR(512) NULL,                   -- 片段开头（截断）—— 让人不下钻也能认出召回对不对
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (run_id, step_index, call_index, hit_rank)
);

-- 【检索调用的参数与汇总单独一行，因为它不是"命中"的属性】
-- query（查的是什么词）与 topK（要了几条）属于**调用**，不属于任何一条命中 ——
-- 把它们冗余到每一行上会在"0 条命中"时整个丢失（而那恰恰是最需要查的一次：
-- 用户说"它答不上来"，第一件要看的就是"它拿什么词去查的、一条都没查到吗"）。
-- 一次没有命中的检索**同样要留痕**，所以它必须有自己的行。
CREATE TABLE IF NOT EXISTS retrieval_call (
  run_id       VARCHAR(64)  NOT NULL,
  step_index   INT          NOT NULL,
  call_index   INT          NOT NULL,
  -- 哪把工具发起的检索。今天只有 search_docs 一把，但记下来才能回答
  -- "将来的第二把检索工具用得怎么样" —— 而那个问题在没有这一列时**无法补算**
  -- （agent_tool_invocation 里有，但那里分不出"检索类"与"非检索类"的调用）
  tool_name    VARCHAR(128) NOT NULL DEFAULT '',
  query        VARCHAR(512) NULL,                   -- 实际拿去检索的查询词
  top_k        INT          NOT NULL DEFAULT 0,     -- 模型要了几条（不是引擎实际返回几条）
  hit_count    INT          NOT NULL DEFAULT 0,     -- 实际命中几条（0 是正常结果，不是故障）
  index_size   INT          NOT NULL DEFAULT 0,     -- 当时索引里有多少块（回答"库是不是空的"）
  elapsed_ms   BIGINT       NOT NULL DEFAULT 0,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (run_id, step_index, call_index)
);

-- 按会话/运行读检索明细时，永远先按 run_id 过滤 —— 与另两张表明细表同一个形状
CREATE INDEX IF NOT EXISTS idx_retrieval_hit_document ON retrieval_hit (document_id);

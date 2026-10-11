-- ============================================================================
--  对话历史的「压缩记录」（滚动摘要）
--
--  代码见 ai.agent.service.chat.compact.ChatCompactionService（写）+
--        ai.agent.service.chat.compact.JdbcChatSummaryStore（读写）+
--        ai.agent.service.agent.memory.SummarizingChatMemory（读：拼进 prompt）
--
--  【它解决的是什么】
--  对话历史变长之后原来只有一处处理：**直接删消息**（写时的条数窗口、
--  发请求前的 token 裁剪）。删掉之后原文在下游任何地方都没有留底，
--  于是"用户当时到底说了什么"变成一个不可回答的问题 —— 而且它不报错。
--
--  现在删改成了压：被挤出去的那一段由模型压成一份摘要。这张表存的就是那份摘要，
--  连同"它覆盖了几轮用户发言"一起 —— 后者是拼接 prompt 时定位用的，见
--  SummarizingChatMemory 的类注释。
--
--  ⚠️ **每次压缩更新同一行，不追加新行**：摘要是增量合并的产物
--  （旧摘要 + 新挤出的对话 → 一份新摘要），模型那一侧只需要一份。
--  保留历次摘要会让"现在生效的是哪一份"变成需要额外判据的问题。
--
--  ⚠️ 单独的"原文归档表"**刻意没有建**：摘要是"给模型读的压缩视图"，
--  而对话原文仍然完整留在 ai.chat.memory 里（压缩只影响拼进 prompt 的那一份，
--  不再回写存储）—— 也就是说原文本来就没丢，再建一张归档表是重复的第二份真相。
--  哪一天需要"跨重启查原文"，那是把 ai.chat.memory 换成 JDBC 实现的事
--  （见 ChatClientConfig#chatMemoryRepository 里留着的那段），不是这张表的事。
-- ============================================================================

CREATE TABLE IF NOT EXISTS chat_conversation_summary (
  conversation_id  VARCHAR(64)  NOT NULL COMMENT '会话 id；一条会话一行（与 ai.chat.memory / agent_run.conversation_id 同一个键）',
  covered_turns    INT          NOT NULL COMMENT '这份摘要覆盖了几轮用户发言（user 消息条数）—— 定位与防漂移都靠它，见 SummarizingChatMemory 的类注释',
  summary          TEXT         NOT NULL COMMENT '摘要正文（模型输出，已剥掉代码围栏）',
  turns_compacted  INT          NOT NULL DEFAULT 0 COMMENT '一共压缩过几次（每次增量合并 +1）；用它区分"压缩过一次"与"反复压缩"，后者说明窗口或预算设小了',
  provider         VARCHAR(64)  NULL COMMENT '写这份摘要的模型名；取不到时记 NULL（不编一个模型名出来，与 ModelTurn.UNKNOWN_MODEL 同一条规矩）',
  created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '第一次压缩的时间',
  updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后一次压缩的时间',
  PRIMARY KEY (conversation_id)
) COMMENT='对话历史的压缩摘要（一行一个会话，增量合并）';

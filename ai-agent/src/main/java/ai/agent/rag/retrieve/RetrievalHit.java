package ai.agent.rag.retrieve;

import ai.agent.rag.chunk.Chunk;

/**
 * 一条召回结果。
 *
 * @param chunk 命中的块（带标题路径，便于判断相关性与排查错误召回）
 * @param score 相关性得分。<b>只在同一次检索内可比</b> ——
 *              不同检索引擎、甚至同一引擎的不同查询之间，分数的量纲都不一样。
 *              这正是后面做混合检索时不能直接相加、必须用 RRF 按"名次"融合的原因。
 * @param rank  名次，从 1 开始
 */
public record RetrievalHit(Chunk chunk, double score, int rank) {
}

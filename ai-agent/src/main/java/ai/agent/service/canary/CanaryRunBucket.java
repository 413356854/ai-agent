package ai.agent.service.canary;

/**
 * 一条"按（提示词指纹, 灰度变体）聚合出来的运行数"。
 *
 * <h2>为什么带的是指纹原文，而不是解析好的版本号</h2>
 * 版本号是从指纹的 {@code v<数字>:} 前缀里解析出来的，而<b>解析规则属于代码、不属于 SQL</b>：
 * 放在 SQL 里（例如 {@code SUBSTRING} + {@code CAST}）会让"解析不出来记 0、而不是丢行"
 * 这条规矩散进 SQL 字符串，而它是有语义的（见 {@link JdbcCanaryStore#versionOf}）。
 * 所以这里原样带指纹，由 Java 解析 —— 报告因此永远能回答"这一行是哪个指纹"。
 *
 * @param fingerprint 系统提示词指纹（形如 {@code v4:9ab12cd34ef5}；空串 = 库里读不到）
 * @param variant {@code canary} / {@code stable} / 空串（没参与分流）
 * @param runs 运行数（COUNT(DISTINCT run_id)，不是步骤数 —— 口径见 {@link JdbcCanaryStore}）
 */
public record CanaryRunBucket(String fingerprint, String variant, int runs) {
}

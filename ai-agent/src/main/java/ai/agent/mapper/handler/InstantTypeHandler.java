package ai.agent.mapper.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * {@code TIMESTAMP} 列 ↔ {@link Instant} 的转换，<b>显式、可空、与时区无关</b>。
 *
 * <h2>为什么每个 javaType="java.time.Instant" 的 <arg> 都要挂上它</h2>
 * 三个理由，每个都是"不写就会静默出错"或"不写就会崩"：
 * <ol>
 *   <li><b>可空</b>：{@code resolved_at} / {@code decided_at} / {@code stop_reason}
 *       这些列在正常业务里就是 NULL。构造器映射遇到 null 时会走拆箱（record 的参数是
 *       {@code Instant}，而 MyBatis 拿着 null 去找构造器），
 *       由 handler 明确返回 null 才不会在"老数据"上炸；</li>
 *   <li><b>库里的时间语义</b>：原先手写代码统一是
 *       {@code ts == null ? null : ts.toInstant()}。
 *       这个写法把 TIMESTAMP 当作"JVM 默认时区的墙上时间"来解释 ——
 *       与 {@code Timestamp.from(instant)} 的写入方向正好互逆，
 *       所以往返是自洽的（这也是本项目一直以来的行为，迁移时逐字保留）；</li>
 *   <li><b>可读</b>：读代码的人不必去猜"这个 Instant 是谁转的"。
 *       转换规则只有这一处，改也只改这一处。</li>
 * </ol>
 *
 * <p>⚠️ 它与 MyBatis 自带的 {@code InstantTypeHandler} 的差别正在第 2 条：
 * 自带的那个走 {@code rs.getObject(col, Instant.class)}，
 * 也就是把"用什么时区解释这个时间"交给了驱动。两者的取舍不该在一次重构里被悄悄换掉。
 */
@MappedTypes(Instant.class)
public class InstantTypeHandler extends BaseTypeHandler<Instant> {

	@Override
	public void setNonNullParameter(PreparedStatement ps, int i, Instant parameter, JdbcType jdbcType)
			throws SQLException {
		ps.setTimestamp(i, Timestamp.from(parameter));
	}

	@Override
	public Instant getNullableResult(ResultSet rs, String columnName) throws SQLException {
		return toInstant(rs.getTimestamp(columnName));
	}

	@Override
	public Instant getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
		return toInstant(rs.getTimestamp(columnIndex));
	}

	@Override
	public Instant getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
		return toInstant(cs.getTimestamp(columnIndex));
	}

	private static Instant toInstant(Timestamp ts) {
		return ts == null ? null : ts.toInstant();
	}

}

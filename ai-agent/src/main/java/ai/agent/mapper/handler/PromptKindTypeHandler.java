package ai.agent.mapper.handler;

import ai.agent.service.prompt.PromptKind;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.apache.ibatis.type.TypeHandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@link PromptKind} 与库里的 {@code kind} 列之间的转换器。
 *
 * <h2>为什么必须有它 —— 用默认的那一个会静默出错</h2>
 * 库里的 {@code kind} 存的<b>不是枚举名</b>，而是 {@link PromptKind#defaultValue()}
 * （{@code "system-agent-tools"} / {@code "system-agent"}），
 * 而 MyBatis 默认的枚举转换器是按 {@code Enum.valueOf}（枚举名）读的。
 * 也就是说默认行为会在读回时抛 {@code IllegalArgumentException: No enum constant}，
 * 或者在写参数时把 {@code AGENT_LOOP} 这个**枚举名**写进库 ——
 * 后者更危险：库多出一种谁都不认识的 kind，而查询按 defaultValue 过滤，那条记录永远消失。
 *
 * <p>所以这里把它固定成"库里存 defaultValue、按 defaultValue 解析"，
 * 与原先手写代码里的 {@code PromptKind.byName(...)} 逐字一致。
 *
 * <h2>写 null 时要显式带 jdbcType</h2>
 * 绑定 null 参数时若不带 jdbcType，某些驱动会直接抛
 * "无法推断参数类型"。这里所有可空列都按 {@link JdbcType#VARCHAR} 处理。
 */
@MappedTypes(PromptKind.class)
public class PromptKindTypeHandler extends BaseTypeHandler<PromptKind> {

	@Override
	public void setNonNullParameter(PreparedStatement ps, int i, PromptKind parameter, JdbcType jdbcType)
			throws SQLException {
		ps.setString(i, parameter.defaultValue());
	}

	@Override
	public PromptKind getNullableResult(ResultSet rs, String columnName) throws SQLException {
		return parse(rs.getString(columnName));
	}

	@Override
	public PromptKind getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
		return parse(rs.getString(columnIndex));
	}

	@Override
	public PromptKind getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
		return parse(cs.getString(columnIndex));
	}

	private static PromptKind parse(String raw) {
		if (raw == null) {
			return null;
		}
		PromptKind kind = PromptKind.byName(raw);
		// 【不静默返回 null】原先是 `PromptKind.byName(...)` 直接塞进 record，
		// 不认识的 kind 会变成 null 字段，然后在**很远的地方**（渲染、比较、switch）
		// 才炸，而那时已经看不出是库里那行数据的问题。这里就地报错，把库里的原值带出来。
		if (kind == null) {
			throw new IllegalArgumentException("库里出现不认识的 prompt kind：" + raw
					+ "（合法取值见 PromptKind 的 defaultValue）");
		}
		return kind;
	}

}

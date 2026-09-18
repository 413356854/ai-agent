package ai.agent.mapper.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;

/**
 * 把结果集里"这一天"这一列读成 {@link LocalDate}。
 *
 * <h2>为什么不是直接用 MyBatis 自带的那个</h2>
 * 因为这一列的类型<b>不保证</b>：它来自 {@code CAST(created_at AS DATE)}，
 * 而 H2 与真 MySQL 对"日期表达式返回什么 Java 类型"并不一致
 * （{@code java.sql.Date} / {@code java.time.LocalDate} / 甚至 {@code Timestamp}）。
 *
 * <p>原先手写代码里有一个四分枝的 {@code toLocalDate(Object)}，就是被这件事逼出来的。
 * 迁移时把那段逻辑搬进这个 handler，而不是"换成 MyBatis 自带的、看起来更干净"——
 * 后者会把一个已经踩过的坑重新埋回去，而且**失败形态是静默的**：
 * 读不出来时那一行会被丢掉或日期错位，看板上的钱按天分错桶，没有任何报错。
 *
 * <p>⚠️ 最后一个分支用 {@code LocalDate.parse(toString())} 兜底，而不是抛异常：
 * 它覆盖的是"驱动给了个我们没预料到的表示"，而那种情况下字符串形式通常是对的。
 * 真解析不了时才会炸 —— 那时错误信息里有原始值。
 */
@MappedTypes(LocalDate.class)
public class LocalDateTypeHandler extends BaseTypeHandler<LocalDate> {

	@Override
	public void setNonNullParameter(PreparedStatement ps, int i, LocalDate parameter, JdbcType jdbcType)
			throws SQLException {
		// 绑定时用 java.sql.Date：时间窗的上下界是与 created_at 比较的，
		// 用 Date 能保证"按天"这个语义在两个库上一致（用 Timestamp 会带上时分秒）
		ps.setDate(i, Date.valueOf(parameter));
	}

	@Override
	public LocalDate getNullableResult(ResultSet rs, String columnName) throws SQLException {
		return from(rs.getObject(columnName), columnName);
	}

	@Override
	public LocalDate getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
		return from(rs.getObject(columnIndex), "第 " + columnIndex + " 列");
	}

	@Override
	public LocalDate getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
		return from(cs.getObject(columnIndex), "第 " + columnIndex + " 列");
	}

	private static LocalDate from(Object raw, String what) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof LocalDate localDate) {
			return localDate;
		}
		if (raw instanceof Date date) {
			return date.toLocalDate();
		}
		if (raw instanceof Timestamp timestamp) {
			return timestamp.toLocalDateTime().toLocalDate();
		}
		return LocalDate.parse(raw.toString());
	}

}

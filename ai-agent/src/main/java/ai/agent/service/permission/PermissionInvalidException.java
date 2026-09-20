package ai.agent.service.permission;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 权限写在<b>写入路径</b>上被拒（工具名不存在、目录前缀不合法、名字重复）。
 *
 * <h2>为什么它继承 {@code ResponseStatusException}</h2>
 * 因为本项目的 {@code ErrorResponseAdvice} 认它 —— 那是让 reason 真的到达客户端的唯一通道
 * （见该类的注释）。继承它意味着这条错误在界面上会显示成一句能读的中文，
 * 而不是一个 500 加一段堆栈。
 *
 * <h2>为什么工具名不存在要在这里拦，而不是等运行期"模型说工具不存在"</h2>
 * 因为那两个错误的<b>位置差得很远</b>：配置时写错一个字母，表现出来的是
 * 某次运行里模型反复试一把不存在的工具，而排查的人会先怀疑模型的工具选择能力。
 * 写入口能拿到 {@code ToolRegistry}，所以这里直接给出"可用的是 a、b、c"。
 */
public class PermissionInvalidException extends ResponseStatusException {

	private static final long serialVersionUID = 1L;

	public PermissionInvalidException(String reason) {
		super(HttpStatus.BAD_REQUEST, reason);
	}

}

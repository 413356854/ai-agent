package ai.agent.service.task;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 任务写入的<b>参数</b>不合法（缺发布人 / 缺任务描述 / 取消没给原因 / 任务不存在）。
 *
 * <p>继承 {@code ResponseStatusException} 的理由与 {@code PermissionInvalidException} 相同：
 * 本项目的 {@code ErrorResponseAdvice} 认它，那段给用户看的中文才真的到得了客户端
 * （否则客户端只看到一个 400 与一段 JSON 骨架）。
 */
public class TaskInvalidException extends ResponseStatusException {

	private static final long serialVersionUID = 1L;

	public TaskInvalidException(String reason) {
		super(HttpStatus.BAD_REQUEST, reason);
	}

}

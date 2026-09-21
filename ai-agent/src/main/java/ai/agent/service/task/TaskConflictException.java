package ai.agent.service.task;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 这次动作与任务<b>当前状态</b>冲突（在等确认项却点推进 / 同一条确认项被答第二遍 /
 * 已经结束的任务还要取消）。
 *
 * <h2>为什么它是 409 而不是 400</h2>
 * 两种情况在界面上的下一步动作完全不同：400 是"你的参数写错了，改一改重试"，
 * 409 是"这条任务现在的状态不允许这件事，先刷新看看它到哪一步了"。
 * 压成一个 500 或 400 会让用户对着一个正确的按钮反复点。
 */
public class TaskConflictException extends ResponseStatusException {

	private static final long serialVersionUID = 1L;

	public TaskConflictException(String reason) {
		super(HttpStatus.CONFLICT, reason);
	}

}

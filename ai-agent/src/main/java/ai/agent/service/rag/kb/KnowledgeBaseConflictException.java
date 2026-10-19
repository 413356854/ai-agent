package ai.agent.service.rag.kb;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 知识库的"当前状态不允许这么做"：名字已被占用、这份文档正在索引中不能重复入队。
 *
 * <p>与 {@link KnowledgeBaseException} 分开是刻意的：两者给用户的下一步不同 ——
 * 400 是"你填的东西哪里不对"（改内容），409 是"换个名字/等它跑完"（改时机）。
 * 混成一个码之后，界面只能提示"请求有误"，而用户不知道要不要重试。
 *
 * <p>由 {@code KnowledgeBaseController} 本地的 {@code @ExceptionHandler} 接住转成 409，
 * 与 {@code AgentTaskController} 对 {@code TaskConflictException} 的处理逐条相同。
 */
public class KnowledgeBaseConflictException extends ResponseStatusException {

	public KnowledgeBaseConflictException(String reason) {
		super(HttpStatus.CONFLICT, reason);
	}

}

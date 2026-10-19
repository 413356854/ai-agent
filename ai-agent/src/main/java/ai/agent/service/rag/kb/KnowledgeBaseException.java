package ai.agent.service.rag.kb;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 知识库的业务校验失败（找不到的 id、不认识的策略、超限的文件、没配的 key）。
 *
 * <p>继承 {@link ResponseStatusException}，由 {@code ErrorResponseAdvice} 统一变成
 * {@code {"status":400,"error":...,"message":...}}——与 {@code TaskInvalidException} 同一种接法。
 * 之所以不学 {@code AgentTaskController} 那样在每个端点手写 {@code badRequest(...)}：
 * 那些校验是"请求体里少了哪个字段"，发生在控制器第一行；这里的校验要读库、
 * 要看配置（比如"这个库的 embedding provider 不认识"），只可能在服务层。
 */
public class KnowledgeBaseException extends ResponseStatusException {

	public KnowledgeBaseException(String reason) {
		super(HttpStatus.BAD_REQUEST, reason);
	}

	public KnowledgeBaseException(String reason, Throwable cause) {
		super(HttpStatus.BAD_REQUEST, reason, cause);
	}

}

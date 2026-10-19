package ai.agent.service.impl.kb;

import ai.agent.config.rag.KnowledgeBaseProperties;
import ai.agent.config.rag.RagProperties;
import ai.agent.service.rag.kb.KbIndexingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时清扫"上次没跑完的索引"。
 *
 * <h2>为什么挂在 {@code ApplicationReadyEvent} 而不是 {@code @PostConstruct}</h2>
 * 与 {@code AgentRunRecoveryOnStartup} 同一条理由：在 {@code @PostConstruct} 那一刻
 * 容器还没起来，此时调 Milvus/数据库失败会把整个启动打回去 ——
 * 而这件事本身是"清理上个进程留下的尾巴"，不该有让应用起不来的权力。
 *
 * <p>关掉 {@code ai.rag.kb.enabled} 时不扫：知识库整块不启用的部署里，
 * 那些停在"已上传"的行不该被这个应用唤醒。
 */
@Component
public class KbIndexRecoveryOnStartup {

	private static final Logger log = LoggerFactory.getLogger(KbIndexRecoveryOnStartup.class);

	private final KbIndexingService indexing;

	private final KnowledgeBaseProperties properties;

	public KbIndexRecoveryOnStartup(KbIndexingService indexing, RagProperties rag) {
		this.indexing = indexing;
		this.properties = rag.getKb();
	}

	@EventListener(ApplicationReadyEvent.class)
	public void sweep() {
		if (!properties.isEnabled()) {
			log.info("知识库已关闭（ai.rag.kb.enabled=false），跳过索引清扫");
			return;
		}
		try {
			indexing.recoverOnStartup();
		}
		catch (RuntimeException e) {
			// 清扫失败不能挡住启动：它清的是上个进程的尾巴，而应用本身是可用的
			log.warn("启动索引清扫失败（应用照常启动）：{}", e.getMessage(), e);
		}
	}

}

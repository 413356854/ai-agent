package ai.agent.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 启动自检：确认 API Key <b>真的被进程读到了</b>，而不是一串没解析出来的占位符。
 *
 * <p><b>为什么需要这个</b>：{@code spring.ai.openai.api-key=${OPENAI_API_KEY}} 在环境变量缺失时
 * <b>不会报错</b> —— Spring Boot 绑定 {@code @ConfigurationProperties} 时会把占位符<b>原文</b>
 * 当成属性值绑进去。于是请求真的带着字符串 {@code ${OPENAI_API_KEY}} 发出去，一路走到模型端才炸：
 *
 * <pre>
 * 401: Authentication Fails, Your api key: ****KEY} is invalid
 *                                                    ^^^^^^
 *                                            这四个字符正是 ${OPENAI_API_KEY} 的结尾
 * </pre>
 *
 * <p>这个报错极易被误读成"key 配错了"，实际原因是<b>环境变量没被当前进程读到</b>：
 * Windows 的环境块是进程启动时从父进程继承的<b>快照</b>，改完变量不重开终端/IDEA 就是读不到。
 * 所以这里在启动阶段就拦住，并且直接把排查步骤打出来，省掉一次"猜 401 原因"的来回。
 */
@Component
public class ApiKeyStartupCheck {

	private static final Logger log = LoggerFactory.getLogger(ApiKeyStartupCheck.class);

	/** 没解析出来的占位符长这样：${OPENAI_API_KEY}。出现它就说明环境变量没读到。 */
	private static final String UNRESOLVED_MARK = "${";

	private final Environment environment;

	public ApiKeyStartupCheck(Environment environment) {
		this.environment = environment;
	}

	@PostConstruct
	void verifyApiKeyWasInjected() {
		String apiKey = readRawProperty();

		if (apiKey == null || apiKey.isBlank() || apiKey.contains(UNRESOLVED_MARK)) {
			throw new IllegalStateException("""

					============================================================
					OPENAI_API_KEY 没有被当前进程读到，拒绝启动。
					（不是 key 填错了，是这个进程的环境变量快照里没有它）

					排查顺序：
					  1) 确认变量已写入「系统/用户环境变量」，且名字就是 OPENAI_API_KEY
					  2) 关掉终端/IDEA 重新打开 —— 环境变量是进程启动时的快照，
					     改完不重启，进程读到的仍是旧值
					  3) 用 ai-agent/run.cmd 启动，它会在启动前先做同样的检查
					  4) 只想临时验证，可在当前窗口注入后再启动：
					       PowerShell:  $env:OPENAI_API_KEY = [Environment]::GetEnvironmentVariable('OPENAI_API_KEY','Machine')
					       cmd:         set "OPENAI_API_KEY=sk-xxx"

					当前读到的值：%s
					============================================================
					""".formatted(describe(apiKey)));
		}

		// 只报"读到了"和长度，不打印内容 —— 别让 key 进日志
		log.info("API Key 已注入（{}****，长度 {}），来源：环境变量/配置", apiKey.substring(0, 3), apiKey.length());
	}

	/**
	 * 读属性原文。
	 *
	 * <p>刻意用 Environment 而不是 {@code @Value("${spring.ai.openai.api-key}")}：
	 * 后者在占位符解析失败时会先抛出难懂的 "Could not resolve placeholder"，
	 * 抢在我们给出可读提示之前就把启动搞挂了。这里把两种失败都收敛到同一个分支。
	 */
	private String readRawProperty() {
		try {
			return environment.getProperty("spring.ai.openai.api-key");
		} catch (IllegalArgumentException e) {
			// 内层 ${OPENAI_API_KEY} 无法解析 —— 同样属于"没读到"
			return null;
		}
	}

	private static String describe(String apiKey) {
		if (apiKey == null) {
			return "(读不到，内层占位符无法解析)";
		}
		if (apiKey.isBlank()) {
			return "(空字符串)";
		}
		return "(未解析的占位符原文：" + apiKey + ")";
	}

}

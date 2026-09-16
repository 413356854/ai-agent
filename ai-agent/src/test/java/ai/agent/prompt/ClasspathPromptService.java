package ai.agent.prompt;

import ai.agent.token.TokenEstimator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * 测试用的提示词服务替身：<b>只读 classpath，不碰数据库</b>。
 *
 * <h2>为什么需要它，而不是在每个测试里 new 一个真的服务</h2>
 * 阶段四之后提示词来自版本库，而大量既有测试（{@code AgentLoopTest}、
 * {@code TokenBudgetGuardTest}、{@code AgentApprovalTest}…）验证的是
 * <b>循环、预算、审批</b>这些东西 —— 它们需要一个系统提示词，但<b>不关心它从哪来</b>。
 * 让这些测试各自去起一个 H2 库，会把"验证循环"变成"验证循环 + 验证存储"，
 * 而后者失败时前者的结论也跟着不可信（一类典型的测试耦合）。
 *
 * <h2>它<b>不</b>验证的东西（这条比代码重要）</h2>
 * 它证明不了热更新：这个替身永远返回 classpath 里那一份。
 * <b>热更新必须由真的服务 + 真的库来验证</b>（见 {@code PromptHotReloadTest}），
 * 否则"我换了一版、它生效了"这句话在这个替身上永远不会成立 ——
 * 而那种测试会给出一份虚假的信心。
 */
public final class ClasspathPromptService implements PromptService {

	private final TokenEstimator estimator;

	public ClasspathPromptService(TokenEstimator estimator) {
		this.estimator = estimator;
	}

	@Override
	public String currentContent(PromptKind kind) {
		try (var in = new ClassPathResource(kind.classpathLocation()).getInputStream()) {
			return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			// 与真实服务一样：读不到就抛，**不返回空串** ——
			// 空串会让"没有系统提示词"以最隐蔽的方式发生
			throw new UncheckedIOException("测试替身读不到 " + kind.classpathLocation(), e);
		}
	}

	@Override
	public String currentFingerprint(PromptKind kind) {
		return "classpath:" + PromptVersion.hashOf(currentContent(kind));
	}

	@Override
	public Optional<PromptVersion> activeVersion(PromptKind kind) {
		// 刻意返回空：这个替身**没有**版本库，所以"当前生效的是第几版"这个问题它答不了。
		// 编一个假版本号会让调用方以为版本管理在这条路径上是活的
		return Optional.empty();
	}

	@Override
	public List<PromptVersion> versions(PromptKind kind) {
		return List.of();
	}

	@Override
	public int estimateFallback(PromptKind kind) {
		return estimator.estimateMessage(currentContent(kind));
	}

	@Override
	public PromptChange save(PromptKind kind, PromptValidator.Input input) {
		throw new UnsupportedOperationException("测试替身不支持写入：热更新测试请用真实的 PromptService");
	}

	@Override
	public PromptVersion activate(PromptKind kind, int version, Integer expectedVersion, String author) {
		throw new UnsupportedOperationException("测试替身不支持切换版本：热更新测试请用真实的 PromptService");
	}

	@Override
	public PromptVersion rollback(PromptKind kind, int targetVersion, String author, String note) {
		throw new UnsupportedOperationException("测试替身不支持回滚：热更新测试请用真实的 PromptService");
	}

	@Override
	public PromptDiff diff(PromptKind kind, int fromVersion, int toVersion) {
		throw new UnsupportedOperationException("测试替身不做版本对比");
	}

}

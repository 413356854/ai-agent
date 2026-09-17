package ai.agent.entity.rag.eval;

import ai.agent.entity.rag.retrieve.RetrievalHit;
import ai.agent.service.rag.eval.SectionLocator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一轮评测的指标汇总 —— <b>这就是"用数字说明提升了多少"里的那个数字</b>。
 *
 * <h2>指标怎么定义，以及为什么选这几个</h2>
 * <ul>
 *   <li><b>Recall@k（命中率）</b>：标准答案所在章节有没有出现在前 k 条里。
 *       这是 RAG 里最重要的一个 —— <b>没召回就一定答不出来</b>，
 *       后面接再好的重排和再强的模型都救不了。所以它是主指标。</li>
 *   <li><b>MRR</b>（第一个正确答案名次的倒数均值）：答案排在第 1 位得 1 分、
 *       第 2 位得 0.5 分。它回答"找得到吗"之外的第二个问题：<b>找得准吗</b>。
 *       排第 1 和第 9 在 Recall@10 里是一样的，但一个能进上下文、一个进不去。</li>
 *   <li><b>ContextPrecision@k</b>：前 k 条里有几条是真的相关，即"召回的纯度"。
 *       它低说明检索往里掺了很多无关块 —— 直接后果是上下文被灌满噪音、token 白花。</li>
 *   <li><b>块数与 token 成本</b>：同一个 Recall，块数少的那个更划算。
 *       而且这条路直接连到阶段一做的 Token 预算 —— 见 {@link #firstThreeHitsTokens}。</li>
 * </ul>
 *
 * <h2>读报告的顺序（写在这里，免得只看一个数就下结论）</h2>
 * 先看 <b>Recall@5</b> 判断"能不能用"，再看 <b>MRR</b> 判断"排得好不好"，
 * 最后看 <b>token 成本</b>判断"贵不贵"。三者要一起看：
 * 分块切得小而碎会让 Recall 上升（一个章节被切成 10 个块，总有 1 个能命中），
 * 但 token 成本暴涨、MRR 反而下降 —— 只看 Recall 会得出"越小越好"的错误结论。
 *
 * @param strategy      策略名
 * @param caseCount     用例数
 * @param chunkCount    索引的块总数
 * @param avgChunkTokens 平均每块 token 数
 * @param recallAt      名次 k → Recall@k
 * @param mrr           第一个正确答案名次的倒数均值
 * @param contextPrecisionAt 名次 k → ContextPrecision@k
 * @param firstThreeHitsTokens 前 3 条命中的 token 之和的均值（≈真正进上下文的量）
 * @param topKTokens    前 k 条命中的 token 之和的均值（粗召回的成本）
 * @param budgetAlignment 按<b>固定 token 预算</b>重新归纳的召回（等成本对照，见 {@link BudgetAlignment}）
 * @param misses        Recall@k 为 0 的用例（最该看的部分）
 * @param perTag        按分组聚合的 Recall@k（看哪一类问题最差）
 */
public record RetrievalMetrics(String strategy, int caseCount, int chunkCount, double avgChunkTokens,
		Map<Integer, Double> recallAt, double mrr, Map<Integer, Double> contextPrecisionAt,
		double firstThreeHitsTokens, double topKTokens, Map<Integer, BudgetAlignment> budgetAlignment,
		List<CaseOutcome> misses, Map<String, TagMetrics> perTag) {

	public RetrievalMetrics {
		recallAt = Map.copyOf(recallAt);
		contextPrecisionAt = Map.copyOf(contextPrecisionAt);
		budgetAlignment = Map.copyOf(budgetAlignment);
		misses = List.copyOf(misses);
		perTag = Map.copyOf(perTag);
	}

	/**
	 * 单条用例的结果。
	 *
	 * @param caseId        用例编号
	 * @param question      提问（排查失败用例时要看原话，措辞和召回强相关）
	 * @param hitRank       第一个正确答案的名次；0 表示前 k 条里一个都没有
	 * @param matchedTitles 命中的章节标题（多条表示召回到了正确答案所在章节的多个块）
	 * @param retrievedTitles 实际召回的前几条属于哪些章节（用于排查"召回了什么"）
	 * @param retrievedTokens 本轮召回的所有块的 token 之和
	 */
	public record CaseOutcome(String caseId, String tag, String question, int hitRank,
			List<String> matchedTitles, List<String> retrievedTitles, int retrievedTokens) {
	}

	/**
	 * 按 tag 聚合的指标。
	 *
	 * <p>为什么要分组：总平均会把问题藏起来。如果 43 条里 20 条是"踩坑"类、
	 * 它们都被切得很好，而 8 条"知识要点"类全军覆没，
	 * 总 Recall 看起来仍然不错 —— 但真正的问题（某类文档结构处理不好）
	 * 要等到用户抱怨才发现。分组之后一眼就能看到。
	 */
	public record TagMetrics(int caseCount, double recallAt5, double mrr, double recallAt10) {
	}

	/**
	 * 计算指标。
	 *
	 * @param strategy   策略名
	 * @param cases      用例
	 * @param hitsByCase 每条用例的召回结果（**按用例 id 索引**）
	 * @param atK        需要报告的 k 值
	 * @param locator    章节定位器：负责判定"这个块算不算命中"。
	 *                   <b>它必须由调用方从语料构建、而不是在这里现算</b> ——
	 *                   两种策略必须共用同一个定位器，否则判定基准不同，
	 *                   两次实验就不可比了
	 * @param chunkCount 块总数
	 * @param avgChunkTokens 平均块 token 数
	 */
	public static RetrievalMetrics of(String strategy, List<EvalCase> cases,
			Map<String, List<RetrievalHit>> hitsByCase, List<Integer> atK, SectionLocator locator,
			int chunkCount, double avgChunkTokens) {
		return of(strategy, cases, hitsByCase, atK, locator, chunkCount, avgChunkTokens, List.of());
	}

	/**
	 * 计算指标（带预算对齐）。
	 *
	 * @param budgets 需要报告的 token 预算档位（空表示不算这一项）。
	 *                <b>它必须是"额外报告的东西"，不能改变上面任何按名次算的指标</b> ——
	 *                两者回答的是不同的问题：按名次是"系统默认行为下好不好"，
	 *                按预算是"成本相同的情况下好不好"。混成一个数就没法拆开看了
	 */
	public static RetrievalMetrics of(String strategy, List<EvalCase> cases,
			Map<String, List<RetrievalHit>> hitsByCase, List<Integer> atK, SectionLocator locator,
			int chunkCount, double avgChunkTokens, List<Integer> budgets) {

		// 保证 k 值有序且去重，报告里的列顺序才稳定
		List<Integer> ks = atK.stream().distinct().sorted().toList();

		Map<Integer, Double> recallSums = new LinkedHashMap<>();
		Map<Integer, Double> precisionSums = new LinkedHashMap<>();
		for (int k : ks) {
			recallSums.put(k, 0.0);
			precisionSums.put(k, 0.0);
		}
		// 预算档位：预算 → 累加器（召回 / 名次倒数 / 实际用掉的 token / 条数）
		List<Integer> budgetSteps = budgets == null ? List.of() : budgets.stream().distinct().sorted().toList();
		Map<Integer, double[]> budgetSums = new LinkedHashMap<>();
		for (int budget : budgetSteps) {
			budgetSums.put(budget, new double[4]);
		}
		double mrrSum = 0;
		double topKTokensSum = 0;
		double firstThreeSum = 0;

		List<CaseOutcome> misses = new ArrayList<>();
		Map<String, List<CaseOutcome>> outcomesByTag = new LinkedHashMap<>();

		for (EvalCase evalCase : cases) {
			List<RetrievalHit> hits = hitsByCase.getOrDefault(evalCase.id(), List.of());

			// 逐条判相关性。
			// 【判定用的是"块覆盖了哪些解析块"，而不是"块的标题路径"】——
			// 后者会让不带标题路径的对照组恒判为不相关。详见 SectionLocator 类注释
			List<Boolean> relevant = new ArrayList<>();
			List<String> retrievedTitles = new ArrayList<>();
			int firstRelevantRank = 0;
			int tokens = 0;
			for (RetrievalHit hit : hits) {
				boolean isRelevant = locator.matches(evalCase, hit.chunk().documentId(),
						hit.chunk().startBlock(), hit.chunk().endBlock());
				relevant.add(isRelevant);
				retrievedTitles.add(describe(hit));
				tokens += hit.chunk().tokenCount();
				if (isRelevant && firstRelevantRank == 0) {
					firstRelevantRank = hit.rank();
				}
			}

			for (int k : ks) {
				int window = Math.min(k, relevant.size());
				boolean hitAtK = false;
				int relevantInWindow = 0;
				for (int i = 0; i < window; i++) {
					if (relevant.get(i)) {
						hitAtK = true;
						relevantInWindow++;
					}
				}
				recallSums.merge(k, hitAtK ? 1.0 : 0.0, Double::sum);
				// 分母用 k 而不是"实际召回条数"：召回不足 k 条本身就是缺陷，
				// 用实际条数当分母会把"召不回来"伪装成"精度很高"
				precisionSums.merge(k, window == 0 ? 0.0 : relevantInWindow / (double) k, Double::sum);
			}

			mrrSum += firstRelevantRank == 0 ? 0.0 : 1.0 / firstRelevantRank;
			topKTokensSum += tokens;
			firstThreeSum += hits.stream().limit(3).mapToInt(h -> h.chunk().tokenCount()).sum();

			// 预算对齐：从名次 1 开始装，装到这个预算装不下为止。
			// 【注意这里不修改上面任何按名次算的量】——两套口径并存但不互相污染
			for (int budget : budgetSteps) {
				int used = 0;
				int count = 0;
				int firstHitUnderBudget = 0;
				boolean anyRelevant = false;
				for (int i = 0; i < hits.size(); i++) {
					int chunkTokens = hits.get(i).chunk().tokenCount();
					// 第一条无条件放行：现实中会让一个略超预算的块进上下文，
					// 而不是"因为这个块太大就什么都不给"。样本数要贴近真实行为
					if (count > 0 && used + chunkTokens > budget) {
						break;
					}
					used += chunkTokens;
					count++;
					if (relevant.get(i)) {
						anyRelevant = true;
						if (firstHitUnderBudget == 0) {
							firstHitUnderBudget = i + 1;
						}
					}
				}
				double[] acc = budgetSums.get(budget);
				acc[0] += anyRelevant ? 1.0 : 0.0;
				acc[1] += firstHitUnderBudget == 0 ? 0.0 : 1.0 / firstHitUnderBudget;
				acc[2] += used;
				acc[3] += count;
			}

			CaseOutcome outcome = new CaseOutcome(evalCase.id(), evalCase.tag(), evalCase.question(),
					firstRelevantRank, evalCase.matchHeadings(), retrievedTitles, tokens);
			outcomesByTag.computeIfAbsent(evalCase.tag(), t -> new ArrayList<>()).add(outcome);

			// 只在"完全没命中"时记为失败用例。命中但排名靠后的情况，
			// MRR 已经反映了，不必再列一遍 —— 列表越长越没人看
			if (firstRelevantRank == 0) {
				misses.add(outcome);
			}
		}

		int n = Math.max(1, cases.size());
		Map<Integer, Double> recallAt = new LinkedHashMap<>();
		Map<Integer, Double> precisionAt = new LinkedHashMap<>();
		for (int k : ks) {
			recallAt.put(k, round(recallSums.get(k) / n));
			precisionAt.put(k, round(precisionSums.get(k) / n));
		}

		Map<String, TagMetrics> perTag = new LinkedHashMap<>();
		for (Map.Entry<String, List<CaseOutcome>> entry : outcomesByTag.entrySet()) {
			List<CaseOutcome> tagOutcomes = entry.getValue();
			double tagN = tagOutcomes.size();
			long hit5 = tagOutcomes.stream().filter(o -> o.hitRank() > 0 && o.hitRank() <= 5).count();
			long hit10 = tagOutcomes.stream().filter(o -> o.hitRank() > 0 && o.hitRank() <= 10).count();
			double tagMrr = tagOutcomes.stream()
					.mapToDouble(o -> o.hitRank() == 0 ? 0.0 : 1.0 / o.hitRank()).sum() / tagN;
			perTag.put(entry.getKey(), new TagMetrics(tagOutcomes.size(),
					round(hit5 / tagN), round(tagMrr), round(hit10 / tagN)));
		}

		Map<Integer, BudgetAlignment> budgetAlignment = new LinkedHashMap<>();
		for (int budget : budgetSteps) {
			double[] acc = budgetSums.get(budget);
			budgetAlignment.put(budget, new BudgetAlignment(budget, round(acc[0] / n), round(acc[1] / n),
					round(acc[2] / n), round(acc[3] / n)));
		}

		return new RetrievalMetrics(strategy, cases.size(), chunkCount, round(avgChunkTokens),
				recallAt, round(mrrSum / n), precisionAt,
				round(firstThreeSum / n), round(topKTokensSum / n), budgetAlignment, misses, perTag);
	}

	/** 召回条目的可读描述：{@code 文档#序号 (面包屑)}，排查错召回时一眼看出它属于哪一节。 */
	private static String describe(RetrievalHit hit) {
		String breadcrumb = hit.chunk().breadcrumb();
		return breadcrumb.isEmpty()
				? hit.chunk().id()
				: hit.chunk().id() + " (" + breadcrumb + ")";
	}

	/**
	 * 四舍五入到 4 位小数。
	 *
	 * <p>指标值保留 4 位是刻意的：两位小数会把"0.62 到 0.65"这种真实提升抹平，
	 * 而 6 位又长得没人会读。同时<b>绝不为了好看而取整</b> ——
	 * 本项目记数的一贯要求是"数字要么是真测出来的，要么不写"。
	 */
	private static double round(double value) {
		return Math.round(value * 10000.0) / 10000.0;
	}

}

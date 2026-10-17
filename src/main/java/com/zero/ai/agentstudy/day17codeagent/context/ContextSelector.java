package com.zero.ai.agentstudy.day17codeagent.context;

import com.zero.ai.agentstudy.day17codeagent.analysis.DependencyGraph;
import com.zero.ai.agentstudy.day17codeagent.analysis.JavaSymbol;
import com.zero.ai.agentstudy.day17codeagent.analysis.RepoMap;
import com.zero.ai.agentstudy.day17codeagent.analysis.SymbolTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 上下文选择器：在预算内，挑出「最该给模型看」的那些代码。
 *
 * <h3>核心矛盾</h3>
 * <p>模型需要上下文才能改对代码，但上下文窗口塞不下整个仓库。
 * 于是问题变成了一个<b>背包问题</b>：给定预算，怎么挑代码让模型的期望正确率最高？</p>
 *
 * <h3>四条评分信号（各自解决一类漏答）</h3>
 * <ol>
 *     <li><b>符号命中（权重最高）</b>：任务里提到的类/方法名直接出现在符号表里。
 *         解决「要改的东西压根没进上下文」；</li>
 *     <li><b>关键词重叠</b>：BM25 风格的自然语言描述匹配。
 *         解决「用户说『下单流程』但没提 {@code checkout}」；</li>
 *     <li><b>依赖联动</b>：命中文件的反向依赖者也要拉进来。
 *         解决「改了接口却不知道谁会编译失败」；</li>
 *     <li><b>路径提示</b>：任务描述里出现了路径片段。权重最低但成本几乎为零。</li>
 * </ol>
 *
 * <h3>降级策略</h3>
 * <p>装不下全文时不应当直接丢弃——按 {@code FULL → SNIPPET → SIGNATURE} 逐级降级。
 * 「给个签名」至少让模型知道这个类存在，比完全不知道强。</p>
 *
 * @author ZeroAi
 */
public class ContextSelector {

    /** 评分权重。 */
    private static final double W_SYMBOL = 6.0;
    private static final double W_KEYWORD = 2.0;
    private static final double W_DEPENDENT = 3.0;
    private static final double W_PATH_HINT = 1.5;

    private final RepoMap repoMap;
    private final SymbolTable symbolTable;
    private final DependencyGraph graph;
    private final Map<String, String> sources;

    public ContextSelector(Map<String, String> sources) {
        this.sources = Map.copyOf(sources);
        this.repoMap = RepoMap.build(this.sources);
        this.symbolTable = SymbolTable.build(this.sources);
        this.graph = DependencyGraph.build(this.sources);
    }

    public RepoMap repoMap() {
        return repoMap;
    }

    public SymbolTable symbolTable() {
        return symbolTable;
    }

    public DependencyGraph graph() {
        return graph;
    }

    /**
     * 为一次任务挑选上下文。
     *
     * @param requirement 自然语言任务描述
     * @param budget      token 预算
     * @param mustInclude 必须包含的文件（例如用户显式 @ 的文件）
     */
    public SelectionResult select(String requirement, ContextBudget budget, List<String> mustInclude) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, StringBuilder> reasons = new LinkedHashMap<>();
        sources.keySet().forEach(p -> {
            scores.put(p, 0.0);
            reasons.put(p, new StringBuilder());
        });

        List<String> queryTerms = TokenEstimator.tokenize(requirement);
        List<JavaSymbol> exactHits = new ArrayList<>();
        for (String term : queryTerms) {
            if (term.length() < 2) {
                continue;
            }
            // 符号名完全等于查询词的，视为强信号
            for (JavaSymbol s : symbolTable.find(term)) {
                exactHits.add(s);
                bump(scores, reasons, s.filePath(), W_SYMBOL, "符号命中:" + term);
            }
            // 符号名包含查询词的，次强
            for (JavaSymbol s : symbolTable.search(term)) {
                bump(scores, reasons, s.filePath(), W_SYMBOL * 0.4, "符号模糊:" + term);
            }
        }

        // 关键词：文件名 + 正文
        String reqLower = requirement.toLowerCase(Locale.ROOT);
        for (String path : sources.keySet()) {
            String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            for (String term : queryTerms) {
                if (term.length() >= 2 && name.contains(term)) {
                    bump(scores, reasons, path, W_KEYWORD * 1.5, "文件名匹配:" + term);
                }
            }
            String body = sources.get(path).toLowerCase(Locale.ROOT);
            int occurrences = 0;
            for (String term : queryTerms) {
                if (term.length() >= 2) {
                    occurrences += countOccurrences(body, term);
                }
            }
            if (occurrences > 0) {
                // 用 sqrt(长度) 归一化，避免「最长的文件天然分最高」
                double norm = occurrences / Math.sqrt(Math.max(1, body.length()));
                bump(scores, reasons, path, W_KEYWORD * norm, "正文命中 " + occurrences + " 次");
            }
        }

        // 路径提示
        for (String path : sources.keySet()) {
            String lower = path.toLowerCase(Locale.ROOT);
            for (String term : queryTerms) {
                if (term.length() >= 3 && lower.contains(term)) {
                    bump(scores, reasons, path, W_PATH_HINT, "路径提示:" + term);
                }
            }
        }

        // 依赖联动：命中文件的反向依赖者一起带上
        List<String> seeds = scores.entrySet().stream()
                .filter(e -> e.getValue() >= W_SYMBOL * 0.4)
                .map(Map.Entry::getKey).toList();
        Set<String> impact = graph.impactSet(seeds);
        for (String dependent : impact) {
            if (scores.containsKey(dependent) && !seeds.contains(dependent)) {
                bump(scores, reasons, dependent, W_DEPENDENT, "被改动方影响");
            }
        }

        // 必选文件
        List<String> mandatory = mustInclude == null ? List.of() : mustInclude;
        for (String path : mandatory) {
            if (scores.containsKey(path)) {
                bump(scores, reasons, path, 100.0, "用户指定必须包含");
            }
        }

        // 排序后按预算贪心装填，装不下就降级
        List<Map.Entry<String, Double>> ranked = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry::getKey))
                .toList();

        List<ContextItem> picked = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        int used = 0;
        int limit = budget.availableForCode();

        for (Map.Entry<String, Double> entry : ranked) {
            if (entry.getValue() <= 0.0) {
                continue;
            }
            String path = entry.getKey();
            String source = sources.get(path);
            String reason = reasons.get(path).toString().replaceAll(",\\s*$", "");

            int fullTokens = TokenEstimator.estimate(source);
            if (used + fullTokens <= limit) {
                picked.add(new ContextItem(path, source, ContextItem.Mode.FULL,
                        fullTokens, entry.getValue(), reason));
                used += fullTokens;
                continue;
            }
            // 降级 1：签名摘要
            String signature = repoMap.render(List.of(path));
            if (signature.isBlank()) {
                // 非 Java 文件没有符号摘要，降级后是空内容。
                // 若照样塞进上下文，等于白占一个位置还让模型以为「这个文件是空的」。
                dropped.add(path);
                continue;
            }
            int sigTokens = TokenEstimator.estimate(signature);
            if (used + sigTokens <= limit) {
                picked.add(new ContextItem(path, signature, ContextItem.Mode.SIGNATURE,
                        sigTokens, entry.getValue(), reason + "（预算不足，降级为签名）"));
                used += sigTokens;
            } else {
                dropped.add(path);
            }
        }

        return new SelectionResult(
                List.copyOf(picked), List.copyOf(dropped), used, budget,
                List.copyOf(exactHits), impact);
    }

    /** 便捷重载：无强制文件。 */
    public SelectionResult select(String requirement, ContextBudget budget) {
        return select(requirement, budget, List.of());
    }

    private static void bump(Map<String, Double> scores, Map<String, StringBuilder> reasons,
                             String path, double delta, String what) {
        if (delta <= 0 || path == null) {
            return;
        }
        scores.merge(path, delta, Double::sum);
        StringBuilder sb = reasons.get(path);
        if (sb != null && !sb.toString().contains(what)) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(what);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }

    /**
     * 选择结果。
     *
     * @param items     被选中并排好序的上下文条目
     * @param dropped   因预算不足被丢弃的文件
     * @param usedTokens 已用 token
     * @param budget    原始预算
     * @param exactHits 精确符号命中（可用于「选址」：改哪个符号）
     * @param impactSet 影响面文件集合
     */
    public record SelectionResult(
            List<ContextItem> items,
            List<String> dropped,
            int usedTokens,
            ContextBudget budget,
            List<JavaSymbol> exactHits,
            Set<String> impactSet
    ) {
        public boolean fits() {
            return usedTokens <= budget.availableForCode();
        }

        public double usage() {
            return budget.usage(usedTokens);
        }

        /** 渲染成完整提示词片段。 */
        public String render() {
            StringBuilder sb = new StringBuilder();
            for (ContextItem item : items) {
                sb.append(item.render()).append("\n\n");
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return String.format("SelectionResult{选中 %d 个文件, 丢弃 %d 个, 占用 %d/%d token (%.1f%%)}",
                    items.size(), dropped.size(), usedTokens,
                    budget.availableForCode(), usage() * 100);
        }
    }
}

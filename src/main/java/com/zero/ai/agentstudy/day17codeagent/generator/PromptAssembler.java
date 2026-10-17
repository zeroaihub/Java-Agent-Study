package com.zero.ai.agentstudy.day17codeagent.generator;

import com.zero.ai.agentstudy.day17codeagent.context.ContextSelector;
import com.zero.ai.agentstudy.day17codeagent.context.TokenEstimator;

import java.util.List;

/**
 * 提示词装配器：把「角色 / 工具 / 仓库地图 / 选中代码 / 需求 / 输出契约」拼成一次请求。
 *
 * <h3>为什么单独做一个类</h3>
 * <p>很多人的第一版会把提示词拼装在业务方法里，写成一段几百行的字符串拼接。
 * 结果很快发现：</p>
 * <ul>
 *     <li>想 A/B 对比「要不要给 RepoMap」时改不动；</li>
 *     <li>想统计「提示词里到底放了什么」时无从下手；</li>
 *     <li>换模型之后格式不对，要全文搜索替换。</li>
 * </ul>
 * <p>装配器把提示词变成<b>有结构的数据</b>，每一块独立可开关、可测量。</p>
 *
 * <h3>输出格式契约是重中之重</h3>
 * <p>必须在系统提示词里<b>写死格式</b>并且给例子。只说「请用 diff 格式」，
 * 模型会给出十种不同的 diff。契约越具体，补丁解析成功率越高。</p>
 *
 * @author ZeroAi
 */
public class PromptAssembler {

    /** 系统提示词：角色 + 铁律 + 输出契约。 */
    public static final String SYSTEM_PROMPT = """
            你是一个运行在受限沙箱中的 Java 代码助手（Code Agent）。

            ## 铁律
            1. 只修改完成用户需求所必需的最少代码，不得顺带重构、改风格、加注释。
            2. 你不知道当前文件的完整内容。SEARCH 片段必须来自「上下文里给出的原文」，
               严禁凭记忆编写 —— 记忆里的代码通常是过期的，会导致补丁打不上。
            3. 一次回答可以包含多个文件，每个文件必须先用 `File: <相对路径>` 声明。
            4. 不要输出除了补丁以外的任何解释性段落。

            ## 输出格式（严格遵守）
            File: <仓库内相对路径>
            <<<<<<< SEARCH
            <要被替换的原文片段，必须与文件当前内容逐字符一致，包含缩进与空行>
            =======
            <替换后的新片段>
            >>>>>>> REPLACE

            若同一片段在文件中出现多次，SEARCH 片段必须多带几行上下文使其唯一。
            """;

    /** 是否附带仓库地图（可开关，便于做消融实验）。 */
    private final boolean includeRepoMap;

    public PromptAssembler(boolean includeRepoMap) {
        this.includeRepoMap = includeRepoMap;
    }

    public static PromptAssembler withRepoMap() {
        return new PromptAssembler(true);
    }

    public static PromptAssembler minimal() {
        return new PromptAssembler(false);
    }

    /**
     * 装配用户侧提示词。
     *
     * @param requirement 用户的自然语言需求
     * @param selection   上下文选择结果
     */
    public String assembleUserPrompt(String requirement, ContextSelector.SelectionResult selection) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 任务\n").append(requirement).append("\n\n");

        if (includeRepoMap && selection != null) {
            sb.append("# 仓库结构概览（只是索引，不是全部内容）\n");
            sb.append(selection.impactSet().isEmpty()
                    ? "（本次未命中任何符号）\n"
                    : "影响面包含 " + selection.impactSet().size() + " 个文件\n");
            sb.append('\n');
        }
        if (selection != null && !selection.items().isEmpty()) {
            sb.append("# 相关代码\n");
            sb.append(selection.render());
        }
        sb.append("# 输出\n请直接给出 SEARCH/REPLACE 补丁。");
        return sb.toString();
    }

    /** 一次性装配成 GenerationRequest。 */
    public GenerationRequest assemble(String requirement,
                                      ContextSelector.SelectionResult selection,
                                      int maxTokens) {
        String user = assembleUserPrompt(requirement, selection);
        return new GenerationRequest(SYSTEM_PROMPT, user, maxTokens, 0.1, null);
    }

    /**
     * 预估「整文件重写」与「SEARCH/REPLACE」两种策略的输出成本差。
     *
     * <p>这是选择补丁策略的量化依据：输出 token 直接等于钱和延迟。</p>
     */
    public static CostComparison compareRewriteVsPatch(List<String> fullFilePaths,
                                                       java.util.Map<String, String> sources,
                                                       List<String> patchTexts) {
        int rewrite = fullFilePaths.stream()
                .map(p -> TokenEstimator.estimate(sources.getOrDefault(p, "")))
                .mapToInt(Integer::intValue).sum();
        int patchCost = patchTexts.stream().mapToInt(TokenEstimator::estimate).sum();
        return new CostComparison(rewrite, patchCost);
    }

    /**
     * 成本对比。
     *
     * @param fullRewriteTokens 整文件重写所需 token
     * @param patchTokens       补丁方式所需 token
     */
    public record CostComparison(int fullRewriteTokens, int patchTokens) {
        public int saved() {
            return fullRewriteTokens - patchTokens;
        }

        public double savedRatio() {
            if (fullRewriteTokens == 0) {
                return 0.0;
            }
            return (double) saved() / fullRewriteTokens;
        }

        @Override
        public String toString() {
            return String.format("整文件重写 %d token vs 补丁 %d token → 节省 %d（%.1f%%）",
                    fullRewriteTokens, patchTokens, saved(), savedRatio() * 100);
        }
    }
}

package com.zero.ai.agentstudy.day17codeagent.gitops;

import com.zero.ai.agentstudy.day17codeagent.review.ReviewComment;

import java.util.List;

/**
 * PR（Merge Request）草稿。
 *
 * <p>让人类评审者最省力的 PR 应该包含：</p>
 * <ol>
 *     <li><b>做了什么</b>：一句话摘要，不要「优化了一些代码」这种废话；</li>
 *     <li><b>为什么做</b>：原始需求；</li>
 *     <li><b>改了哪些文件</b>：清单 + 行数量级；</li>
 *     <li><b>验证情况</b>：编译是否通过、测试通过率、覆盖率；</li>
 *     <li><b>风险与关注点</b>：agent 自己给出的提示（这最能建立信任）；</li>
 *     <li><b>已知不足</b>：诚实说明哪些地方没有覆盖。</li>
 * </ol>
 *
 * @param title      标题
 * @param summary    一句话摘要
 * @param motivation 原始需求
 * @param details    变更明细（已格式化）
 * @param verification 验证情况
 * @param concerns   风险与关注点
 * @param labels     建议的标签
 * @param riskLevel  整体风险
 * @author ZeroAi
 */
public record PullRequestDraft(
        String title,
        String summary,
        String motivation,
        String details,
        String verification,
        List<String> concerns,
        List<String> labels,
        FileChange.RiskLevel riskLevel
) {

    /** 渲染成 Markdown，可直接作为 PR 正文。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append("> ").append(summary).append("\n\n");

        sb.append("## 变更动机\n").append(motivation).append("\n\n");

        sb.append("## 变更明细\n");
        sb.append(details).append('\n');

        sb.append("\n## 验证情况\n").append(verification).append('\n');

        if (!concerns.isEmpty()) {
            sb.append("\n## 需要关注\n");
            concerns.forEach(c -> sb.append("- ").append(c).append('\n'));
        }

        sb.append("\n## 标签\n");
        labels.forEach(l -> sb.append("`").append(l).append("` "));
        sb.append("\n\n---\n");
        sb.append("_本 PR 由 Code Agent 自动生成，请重点核对上述「需要关注」部分。_");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "PullRequestDraft{标题=" + title + ", 风险=" + riskLevel.display() + "}";
    }

    /** 由 ReviewComment 列表提炼出「需要关注」条目。 */
    public static List<String> concernsFrom(List<ReviewComment> comments) {
        return comments.stream()
                .filter(c -> c.severity() == ReviewComment.Severity.BLOCKER
                        || c.severity() == ReviewComment.Severity.MAJOR)
                .map(c -> c.rule() + "：" + c.message())
                .distinct()
                .toList();
    }
}

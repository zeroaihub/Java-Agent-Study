package com.zero.ai.agentstudy.day17codeagent.gitops;

import com.zero.ai.agentstudy.day17codeagent.patch.TextPatch;
import com.zero.ai.agentstudy.day17codeagent.review.ReviewComment;

import java.util.ArrayList;
import java.util.List;

/**
 * PR 草稿生成器。
 *
 * <p>把「变更集 + 验证结果 + 评审意见」合成一份人类能直接读的 PR。</p>
 *
 * @author ZeroAi
 */
public final class PrDraftGenerator {

    private PrDraftGenerator() {
    }

    /**
     * 生成 PR 草稿。
     *
     * @param requirement 原始需求
     * @param patches     本次应用的补丁
     * @param bundle      变更集
     * @param compiled    是否编译通过
     * @param testSummary 测试情况描述（可为 null）
     * @param review      评审汇总（可为 null）
     */
    public static PullRequestDraft generate(String requirement,
                                            List<TextPatch> patches,
                                            ChangeBundle bundle,
                                            boolean compiled,
                                            String testSummary,
                                            List<ReviewComment> review) {
        String title = titleOf(requirement, bundle);
        String summary = String.format("修改 %d 个文件（+%d / -%d 行），整体风险 %s。",
                bundle.fileCount(), bundle.totalAdded(), bundle.totalRemoved(),
                bundle.overallRisk().display());

        StringBuilder details = new StringBuilder();
        details.append("| 文件 | 类型 | 增删 | 风险 |\n|---|---|---|---|\n");
        for (FileChange change : bundle.changes()) {
            details.append(String.format("| `%s` | %s | +%d / -%d | %s |%n",
                    change.path(), change.changeType().display(),
                    change.addedLines(), change.removedLines(),
                    change.risk().display()));
        }
        details.append("\n补丁共 ").append(patches.size()).append(" 处：\n");
        patches.forEach(p -> details.append("- ").append(p.filePath())
                .append(" (").append(p.stats()).append(')')
                .append(p.note() == null ? "" : " —— " + p.note()).append('\n'));

        StringBuilder verification = new StringBuilder();
        verification.append("- 编译验证：").append(compiled ? "✅ 通过" : "❌ 未通过").append('\n');
        if (testSummary != null) {
            verification.append("- 测试验证：").append(testSummary).append('\n');
        } else {
            verification.append("- 测试验证：未执行\n");
        }
        if (review != null && !review.isEmpty()) {
            long blockers = review.stream()
                    .filter(c -> c.severity() == ReviewComment.Severity.BLOCKER).count();
            long majors = review.stream()
                    .filter(c -> c.severity() == ReviewComment.Severity.MAJOR).count();
            verification.append("- 静态评审：共 ").append(review.size())
                    .append(" 条意见（必须修改 ").append(blockers)
                    .append("，建议 ").append(majors).append("）\n");
        }

        List<String> concerns = new ArrayList<>();
        if (!compiled) {
            concerns.add("编译未通过，**请勿合并**");
        }
        if (review != null && review.stream().anyMatch(ReviewComment::isBlocking)) {
            concerns.add("存在必须修改的评审问题，建议修改后重新评审");
        }
        if (review != null) {
            concerns.addAll(PullRequestDraft.concernsFrom(review));
        }
        if (bundle.touchesCoreSource()) {
            concerns.add("改动触及核心源码，建议由该模块的负责人复核");
        }
        if (bundle.overallRisk() == FileChange.RiskLevel.HIGH) {
            concerns.add("变更体量较大，建议拆分为多个更小的 PR 以便评审");
        }

        List<String> labels = new ArrayList<>(List.of("code-agent", "auto-generated"));
        labels.add("risk-" + bundle.overallRisk().name().toLowerCase());
        if (!compiled) {
            labels.add("do-not-merge");
        }

        return new PullRequestDraft(title, summary, requirement, details.toString(),
                verification.toString(), List.copyOf(concerns), List.copyOf(labels),
                bundle.overallRisk());
    }

    private static String titleOf(String requirement, ChangeBundle bundle) {
        String clean = requirement.replaceAll("\\s+", " ").strip();
        if (clean.length() > 40) {
            clean = clean.substring(0, 40) + "…";
        }
        String kind = bundle.changes().size() == 1
                ? bundle.changes().get(0).changeType().display() : "重构";
        return "[" + kind + "] " + clean;
    }
}

package com.zero.ai.agentstudy.day17codeagent.gitops;

import com.zero.ai.agentstudy.day17codeagent.patch.PatchApplier;
import com.zero.ai.agentstudy.day17codeagent.patch.PatchParser;
import com.zero.ai.agentstudy.day17codeagent.patch.TextPatch;
import com.zero.ai.agentstudy.day17codeagent.repo.RepoSnapshot;
import com.zero.ai.agentstudy.day17codeagent.repo.SampleRepository;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;
import com.zero.ai.agentstudy.day17codeagent.review.RuleBasedReviewer;

import java.util.List;

/**
 * Chapter 17 演示：GitOps 变更管理与 PR 草稿生成。
 *
 * @author ZeroAi
 */
public class Chapter17Demo {

    public static void main(String[] args) {
        // ---- 1. 一次性任务：改三个地方 ----
        String requirement = "Money 需要支持乘法运算，并补充对应单元测试";

        VirtualFileSystem vfs = VirtualFileSystem.temp("gitops-demo");
        vfs.writeAll(SampleRepository.files());
        // 预先放一个测试文件占位：新建文件应当用 write_file，而不是 SEARCH/REPLACE
        vfs.write("src/test/java/demo/order/MoneyTest.java",
                "package demo.order;\n");
        RepoSnapshot before = vfs.snapshot();

        String agentOutput = """
                File: src/main/java/demo/order/Money.java
                <<<<<<< SEARCH
                    public Money add(Money other) {
                        return new Money(this.amount.add(other.amount));
                    }
                =======
                    public Money add(Money other) {
                        return new Money(this.amount.add(other.amount));
                    }

                    public Money multiply(long factor) {
                        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)));
                    }
                >>>>>>> REPLACE

                File: src/test/java/demo/order/MoneyTest.java
                <<<<<<< SEARCH
                package demo.order;
                =======
                package demo.order;

                public class MoneyTest {
                    public static void main(String[] args) {
                        System.out.println("PASS: placeholder");
                    }
                }
                >>>>>>> REPLACE
                """;

        System.out.println("=== [1] 应用 Agent 产出的补丁 ===");
        var parsed = PatchParser.parse(agentOutput, null);
        PatchApplier applier = PatchApplier.strict();
        List<TextPatch> appliedPatches = new java.util.ArrayList<>();
        for (TextPatch patch : parsed.patches()) {
            var result = applier.applyTo(vfs, patch);
            System.out.println("  " + result);
            if (result.success()) {
                appliedPatches.add(patch.withNote(patch.stats().toString()));
            }
        }

        // ---- 2. 变更集 ----
        RepoSnapshot after = vfs.snapshot();
        ChangeBundle bundle = ChangeBundle.from(before, after);
        System.out.println("\n=== [2] 变更集 ===");
        System.out.println("  " + bundle);
        bundle.changes().forEach(c -> System.out.println("    " + c));

        System.out.println("\n  关键：没有任何内容变化的 4 个原文件不出现在变更集里。");
        System.out.println("  → 若用「修改时间」判定改动，这里会多出一堆 +0/-0 的噪声条目。");

        // ---- 3. 评审结果汇入 PR ----
        System.out.println("\n=== [3] 对本次改动做规则评审 ===");
        RuleBasedReviewer reviewer = new RuleBasedReviewer();
        var comments = reviewer.review(SampleRepository.MONEY,
                before.get(SampleRepository.MONEY), after.get(SampleRepository.MONEY));
        var reviewSummary = new RuleBasedReviewer.ReviewSummary(comments);
        System.out.println("  " + reviewSummary);
        comments.forEach(c -> System.out.println("    " + c));

        // ---- 4. 生成 PR 草稿 ----
        System.out.println("\n=== [4] 生成 PR 草稿 ===");
        PullRequestDraft draft = PrDraftGenerator.generate(
                requirement, appliedPatches, bundle, true,
                "MoneyTest 1/1 通过", comments);
        System.out.println(draft.render());

        // ---- 5. 失败场景：编译没过时 PR 必须被打标 ----
        System.out.println("\n=== [5] 编译失败时，PR 必须自带不可合并标记 ===");
        PullRequestDraft broken = PrDraftGenerator.generate(
                requirement, appliedPatches, bundle, false,
                "未执行（编译未通过）", comments);
        System.out.println("  标签 = " + broken.labels());
        System.out.println("  需要关注：");
        broken.concerns().forEach(c -> System.out.println("    - " + c));

        // ---- 6. 风险分级演示 ----
        System.out.println("\n=== [6] 风险分级：核心目录 + 大改动 = 高风险 ===");
        List<FileChange> samples = List.of(
                new FileChange(FileChange.ChangeType.MODIFIED, "README.md", 3, 1, ""),
                new FileChange(FileChange.ChangeType.MODIFIED,
                        "src/main/java/demo/order/Money.java", 8, 2, ""),
                new FileChange(FileChange.ChangeType.MODIFIED,
                        "src/main/java/demo/order/OrderService.java", 80, 20, ""));
        samples.forEach(c -> System.out.println("    " + c));
        System.out.println("  → 风险等级把「这个 PR 该找谁审」从拍脑袋变成可自动化规则");
    }
}

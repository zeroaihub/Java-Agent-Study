package com.zero.ai.agentstudy.day17codeagent.orchestrator;

import com.zero.ai.agentstudy.day17codeagent.generator.StubCodeModel;
import com.zero.ai.agentstudy.day17codeagent.metering.TaskBudget;
import com.zero.ai.agentstudy.day17codeagent.repo.SampleRepository;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;

/**
 * Chapter 20 演示：完整 Code Agent 集成实战。
 *
 * <p>一次请求跑完：选址 → 生成 → 安全检查 → 打补丁 → 编译自修复 → 评审 → 变更集 → PR。</p>
 *
 * @author ZeroAi
 */
public class Chapter20Demo {

    public static void main(String[] args) {
        // ---- 场景 1：正常任务 ----
        System.out.println("=== [1] 正常任务：给 Money 增加 multiply ===");
        VirtualFileSystem vfs = VirtualFileSystem.temp("pipeline-1");
        vfs.writeAll(SampleRepository.files());
        CodeTaskResult result = DefaultCodeAgentPipeline.run(
                vfs, StubCodeModel.reliable(),
                "请给 Money 增加 multiply 方法，支持乘以一个整数倍率");
        System.out.println("  " + result);
        System.out.println(result.trace().render());
        System.out.println("  可合并 = " + result.mergeable());
        if (result.prDraft() != null) {
            System.out.println("\n  --- PR 草稿 ---");
            result.prDraft().render().lines().limit(14)
                    .forEach(l -> System.out.println("    " + l));
        }

        // ---- 场景 2：安全策略拒绝 ----
        System.out.println("\n=== [2] 被安全策略拒绝：补丁想改 CI 配置 ===");
        VirtualFileSystem vfs2 = VirtualFileSystem.temp("pipeline-2");
        vfs2.writeAll(SampleRepository.files());
        vfs2.write(".github/workflows/ci.yml", "name: ci\n");
        CodeTaskResult result2 = DefaultCodeAgentPipeline.run(
                vfs2, new CiConfigModel(), "优化 CI 流程");
        System.out.println("  " + result2);
        System.out.println("  说明 = " + result2.message());
        System.out.println("  → 安全审查发生在「打补丁之前」，被拒后不会浪费任何编译与测试资源。");

        // ---- 场景 3：没有写权限 ----
        System.out.println("\n=== [3] 权限不足：只读用户提交编码任务 ===");
        VirtualFileSystem vfs3 = VirtualFileSystem.temp("pipeline-3");
        vfs3.writeAll(SampleRepository.files());
        var readOnlyCtx = com.zero.ai.agentstudy.day17codeagent.core.model.CodeTaskContext.of(
                com.zero.ai.agentstudy.day17codeagent.core.model.TenantId.of("tenant-acme"),
                com.zero.ai.agentstudy.day17codeagent.core.model.RepoId.of("order-service"),
                com.zero.ai.agentstudy.day17codeagent.core.model.UserId.of("intern"),
                java.util.Set.of("VIEWER"),
                com.zero.ai.agentstudy.day17codeagent.core.model.TaskId.of("task-403"));
        try {
            new DefaultCodeAgentPipeline(StubCodeModel.reliable(), TaskBudget.defaults())
                    .execute(readOnlyCtx, vfs3, "给 Money 增加 multiply", 3);
            System.out.println("  ✗ 竟然通过了");
        } catch (RuntimeException e) {
            System.out.println("  ✓ 被拦截：" + e.getMessage());
        }

        // ---- 场景 4：会犯错的模型 + 自修复 ----
        System.out.println("\n=== [4] 会犯错的模型：自修复循环把它救回来 ===");
        VirtualFileSystem vfs4 = VirtualFileSystem.temp("pipeline-4");
        vfs4.writeAll(SampleRepository.files());
        CodeTaskResult result4 = DefaultCodeAgentPipeline.run(
                vfs4, new RepairableModel(),
                "请给 Money 增加 multiply 方法，支持乘以一个整数倍率");
        System.out.println("  " + result4);
        result4.trace().steps().forEach(s -> System.out.println("    " + s));

        // 对照：模型每次只会「整块替换」时，自修复也救不回来了
        System.out.println("\n  【对照】模型每轮都给同一个全块替换补丁：");
        VirtualFileSystem vfs5 = VirtualFileSystem.temp("pipeline-5");
        vfs5.writeAll(SampleRepository.files());
        CodeTaskResult result5 = DefaultCodeAgentPipeline.run(
                vfs5, StubCodeModel.flaky(),
                "请给 Money 增加 multiply 方法，支持乘以一个整数倍率");
        System.out.println("  " + result5);
        System.out.println("  → 自修复不是万能的。它只能修「能被一个补丁修正」的错误，");
        System.out.println("    救不了「模型根本没理解反馈」的情况——这正是第 13 章无进展检测要早停的原因。");

        // ---- 5. 沉淀为评测样本 ----
        System.out.println("\n=== [5] 线上任务沉淀为评测样本 ===");
        System.out.println("  " + result.toTaskOutcome(StubCodeModel.reliable().modelId()));
        System.out.println("  → 把真实任务自动转成评测集，是持续提升 Agent 能力的唯一可行路径：");
        System.out.println("    人工标注太慢，而每一次真实任务本身就是一份标注数据。");
    }

    /** 用来演示「安全策略拒绝」的桩模型：它想去改 CI 配置。 */
    private static class CiConfigModel implements com.zero.ai.agentstudy.day17codeagent.generator.CodeModel {
        @Override
        public String modelId() {
            return "ci-config-model";
        }

        @Override
        public com.zero.ai.agentstudy.day17codeagent.generator.GenerationOutcome generate(
                com.zero.ai.agentstudy.day17codeagent.generator.GenerationRequest request) {
            String text = """
                    File: .github/workflows/ci.yml
                    <<<<<<< SEARCH
                    name: ci
                    =======
                    name: ci
                      secrets: inherit
                    >>>>>>> REPLACE
                    """;
            return new com.zero.ai.agentstudy.day17codeagent.generator.GenerationOutcome(
                    text, request.estimateInputTokens(), 120, 1L, modelId(), false);
        }
    }

    /**
     * 用来演示「自修复成功」的桩模型：第一次给出缺分号的补丁，之后给出精准的补分号补丁。
     */
    private static class RepairableModel implements com.zero.ai.agentstudy.day17codeagent.generator.CodeModel {
        private int calls;

        @Override
        public String modelId() {
            return "repairable-model";
        }

        @Override
        public com.zero.ai.agentstudy.day17codeagent.generator.GenerationOutcome generate(
                com.zero.ai.agentstudy.day17codeagent.generator.GenerationRequest request) {
            calls++;
            String text = calls == 1
                    ? """
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
                            return new Money(this.amount.multiply(BigDecimal.valueOf(factor)))
                        }
                    >>>>>>> REPLACE
                    """
                    : """
                    File: src/main/java/demo/order/Money.java
                    <<<<<<< SEARCH
                        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)))
                    =======
                        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)));
                    >>>>>>> REPLACE
                    """;
            return new com.zero.ai.agentstudy.day17codeagent.generator.GenerationOutcome(
                    text, request.estimateInputTokens(), 150, 1L, modelId(), false);
        }
    }
}

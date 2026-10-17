package com.zero.ai.agentstudy.day17codeagent.observability;

import com.zero.ai.agentstudy.day17codeagent.context.ContextBudget;
import com.zero.ai.agentstudy.day17codeagent.context.ContextSelector;
import com.zero.ai.agentstudy.day17codeagent.generator.GenerationOutcome;
import com.zero.ai.agentstudy.day17codeagent.generator.GenerationRequest;
import com.zero.ai.agentstudy.day17codeagent.generator.PromptAssembler;
import com.zero.ai.agentstudy.day17codeagent.generator.StubCodeModel;
import com.zero.ai.agentstudy.day17codeagent.metering.BudgetGuard;
import com.zero.ai.agentstudy.day17codeagent.metering.TaskBudget;
import com.zero.ai.agentstudy.day17codeagent.patch.PatchApplier;
import com.zero.ai.agentstudy.day17codeagent.patch.PatchParser;
import com.zero.ai.agentstudy.day17codeagent.patch.TextPatch;
import com.zero.ai.agentstudy.day17codeagent.repo.SampleRepository;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;
import com.zero.ai.agentstudy.day17codeagent.verify.CompileVerifier;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Chapter 19 演示：可观测性与成本计量。
 *
 * @author ZeroAi
 */
public class Chapter19Demo {

    public static void main(String[] args) throws Exception {
        String requirement = "请给 Money 增加 multiply 方法，支持乘以一个整数倍率";

        VirtualFileSystem vfs = VirtualFileSystem.temp("observability-demo");
        vfs.writeAll(SampleRepository.files());

        TaskTrace trace = TaskTrace.start("trace-demo-001", "task-multiply");
        BudgetGuard guard = BudgetGuard.of(TaskBudget.defaults());
        PromptAssembler assembler = PromptAssembler.withRepoMap();
        StubCodeModel model = StubCodeModel.reliable();

        // ---- 1. 上下文选择 ----
        ContextSelector.SelectionResult selection = trace.timed("上下文选择",
                () -> new ContextSelector(vfs.snapshot().files())
                        .select(requirement, ContextBudget.of32k()));

        // ---- 2. 装配提示词 ----
        GenerationRequest request = trace.timed("提示词装配",
                () -> assembler.assemble(requirement, selection, 4096));

        // ---- 3. 模型调用（带预算检查）----
        GenerationOutcome outcome;
        long t0 = System.currentTimeMillis();
        try {
            guard.acquireModelCall(request.estimateInputTokens());
            outcome = model.generate(request);
            guard.settleTokens(outcome.totalTokens());
            trace.step("模型调用", com.zero.ai.agentstudy.day17codeagent.observability
                            .AgentStep.Status.SUCCESS,
                    System.currentTimeMillis() - t0, outcome.totalTokens(),
                    "model=" + outcome.modelId());
        } catch (RuntimeException e) {
            trace.step("模型调用", com.zero.ai.agentstudy.day17codeagent.observability
                            .AgentStep.Status.FAILED,
                    System.currentTimeMillis() - t0, 0, e.getMessage());
            System.out.println("  模型调用被预算拦下：" + e.getMessage());
            return;
        }

        // ---- 4. 补丁解析与应用 ----
        long t1 = System.currentTimeMillis();
        PatchParser.ParsedPatches parsed = PatchParser.parse(outcome.text(), null);
        int applied = 0;
        for (TextPatch patch : parsed.patches()) {
            if (PatchApplier.strict().applyTo(vfs, patch).success()) {
                applied++;
            }
        }
        trace.step("补丁应用", applied == parsed.patches().size()
                        ? com.zero.ai.agentstudy.day17codeagent.observability
                        .AgentStep.Status.SUCCESS
                        : com.zero.ai.agentstudy.day17codeagent.observability
                        .AgentStep.Status.DEGRADED,
                System.currentTimeMillis() - t1, 0,
                applied + "/" + parsed.patches().size() + " 个应用成功");

        // ---- 5. 编译 ----
        long t2 = System.currentTimeMillis();
        Path classes = Files.createTempDirectory("day17-obs-classes");
        CompileVerifier.CompilationResult compiled =
                CompileVerifier.defaultVerifier().compile(vfs, classes);
        trace.step("编译验证", compiled.success()
                        ? com.zero.ai.agentstudy.day17codeagent.observability
                        .AgentStep.Status.SUCCESS
                        : com.zero.ai.agentstudy.day17codeagent.observability
                        .AgentStep.Status.FAILED,
                System.currentTimeMillis() - t2, 0, compiled.summary());

        // ---- 6. 打印时间线 ----
        System.out.println("=== [1] 一次任务的完整时间线 ===");
        System.out.println(trace.render());

        System.out.println("\n=== [2] 预算护栏状态 ===");
        System.out.println("  " + guard);
        System.out.println("  → 注意「步骤耗时」与「墙钟时间」的差：");
        System.out.println("    差值就是没被 timed() 包住的部分（I/O、GC 等）。差太多说明埋点不够。");

        // ---- 7. 预算拦截演示 ----
        System.out.println("\n=== [3] 预算拦截：把 token 上限压到很低 ===");
        TaskBudget tiny = new TaskBudget(500, 2, 1, 60_000L, 0.03);
        BudgetGuard tinyGuard = BudgetGuard.of(tiny);
        int calls = 0;
        try {
            for (int i = 0; i < 5; i++) {
                tinyGuard.acquireModelCall(request.estimateInputTokens());
                GenerationOutcome o = model.generate(request);
                tinyGuard.settleTokens(o.totalTokens());
                calls++;
            }
        } catch (RuntimeException e) {
            System.out.println("  第 " + (calls + 1) + " 次调用被拦下：");
            System.out.println("    " + e.getMessage());
            System.out.println("  成功调用 " + calls + " 次，累计 " + tinyGuard.usedTokens()
                    + " token，预计花费 ¥" + String.format("%.4f", tinyGuard.estimatedCost()));
        }

        System.out.println("\n=== [4] 自修复轮次上限 ===");
        BudgetGuard roundGuard = BudgetGuard.of(new TaskBudget(20_000, 10, 1, 60_000L, 0.03));
        roundGuard.acquireRepairRound();
        System.out.println("  第 1 轮通过，剩余可用轮次 = "
                + (roundGuard.budget().maxRepairRounds() - roundGuard.repairRounds()));
        try {
            roundGuard.acquireRepairRound();
            System.out.println("  ✗ 竟然通过了第二轮");
        } catch (RuntimeException e) {
            System.out.println("  ✓ 第 2 轮被拦下：" + e.getMessage());
        }

        System.out.println("\n=== [5] 并发安全：check-then-act 竞态 ===");
        System.out.println("  如果先 if (used + n <= max) 再 used += n，两个线程会同时通过检查。");
        System.out.println("  本类的 acquire* 与 settle* 全部 synchronized，保证检查与扣减原子。");

        System.out.println("\n=== [6] 成本换算 ===");
        TaskBudget b = TaskBudget.defaults();
        for (int tokens : new int[]{1_000, 10_000, 20_000}) {
            System.out.printf("  %,7d token → ¥%.3f%n", tokens, b.estimateCost(tokens));
        }
        System.out.println("  → 一个自修复失控的循环跑 20 轮 × 6 次调用，");
        System.out.println("    就是这个数字乘以 120。这就是为什么必须有预算护栏。");
    }
}

package com.zero.ai.agentstudy.day17codeagent.orchestrator;

import com.zero.ai.agentstudy.day17codeagent.context.ContextBudget;
import com.zero.ai.agentstudy.day17codeagent.context.ContextSelector;
import com.zero.ai.agentstudy.day17codeagent.core.common.CodeException;
import com.zero.ai.agentstudy.day17codeagent.core.common.CodeErrorCode;
import com.zero.ai.agentstudy.day17codeagent.core.model.CodeTaskContext;
import com.zero.ai.agentstudy.day17codeagent.core.model.RepoId;
import com.zero.ai.agentstudy.day17codeagent.core.model.TaskId;
import com.zero.ai.agentstudy.day17codeagent.generator.CodeModel;
import com.zero.ai.agentstudy.day17codeagent.generator.GenerationOutcome;
import com.zero.ai.agentstudy.day17codeagent.generator.GenerationRequest;
import com.zero.ai.agentstudy.day17codeagent.generator.PromptAssembler;
import com.zero.ai.agentstudy.day17codeagent.gitops.ChangeBundle;
import com.zero.ai.agentstudy.day17codeagent.gitops.PrDraftGenerator;
import com.zero.ai.agentstudy.day17codeagent.gitops.PullRequestDraft;
import com.zero.ai.agentstudy.day17codeagent.iterate.FixGenerator;
import com.zero.ai.agentstudy.day17codeagent.iterate.SelfRepairLoop;
import com.zero.ai.agentstudy.day17codeagent.metering.BudgetGuard;
import com.zero.ai.agentstudy.day17codeagent.metering.TaskBudget;
import com.zero.ai.agentstudy.day17codeagent.observability.AgentStep;
import com.zero.ai.agentstudy.day17codeagent.observability.TaskTrace;
import com.zero.ai.agentstudy.day17codeagent.patch.PatchApplier;
import com.zero.ai.agentstudy.day17codeagent.patch.PatchParser;
import com.zero.ai.agentstudy.day17codeagent.patch.TextPatch;
import com.zero.ai.agentstudy.day17codeagent.repo.RepoSnapshot;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;
import com.zero.ai.agentstudy.day17codeagent.review.RuleBasedReviewer;
import com.zero.ai.agentstudy.day17codeagent.safety.PatchSafetyChecker;
import com.zero.ai.agentstudy.day17codeagent.safety.SecretScanner;
import com.zero.ai.agentstudy.day17codeagent.verify.CompileVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Code Agent 主流水线：把前面 19 章的能力串成一条可交付的链路。
 *
 * <pre>
 *   选上下文 → 装配提示词 → 调模型 → 解析补丁 → 安全审查 → 应用补丁
 *        → 编译（含自修复）→ 跑测试 → 规则评审 → 变更集 → PR 草稿 → Trace
 * </pre>
 *
 * <h3>编排层的三条设计原则</h3>
 * <ol>
 *     <li><b>编排不写业务逻辑</b>：每一步都委托给对应模块，这里只负责顺序与决策；</li>
 *     <li><b>失败要早、要准</b>：安全审查在应用之前，能省下后续所有无效验证；</li>
 *     <li><b>每一步都要落 Trace</b>：没有 Trace 的 Agent 出问题只能靠猜。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class DefaultCodeAgentPipeline {

    private final CodeModel model;
    private final PromptAssembler assembler;
    private final PatchApplier applier;
    private final PatchSafetyChecker safety;
    private final SecretScanner secretScanner;
    private final CompileVerifier verifier;
    private final RuleBasedReviewer reviewer;
    private final TaskBudget budget;

    public DefaultCodeAgentPipeline(CodeModel model, TaskBudget budget) {
        this(model, budget, PromptAssembler.withRepoMap(),
                PatchApplier.tolerant(), PatchSafetyChecker.defaults(),
                new SecretScanner(), CompileVerifier.defaultVerifier(),
                new RuleBasedReviewer());
    }

    public DefaultCodeAgentPipeline(CodeModel model, TaskBudget budget, PromptAssembler assembler,
                                    PatchApplier applier, PatchSafetyChecker safety,
                                    SecretScanner secretScanner, CompileVerifier verifier,
                                    RuleBasedReviewer reviewer) {
        this.model = model;
        this.budget = budget;
        this.assembler = assembler;
        this.applier = applier;
        this.safety = safety;
        this.secretScanner = secretScanner;
        this.verifier = verifier;
        this.reviewer = reviewer;
    }

    /**
     * 执行一次编码任务。
     *
     * @param context    任务上下文（租户 / 仓库 / 权限 / 分支）
     * @param vfs        仓库
     * @param requirement 自然语言需求
     * @param maxRepairRounds 自修复最大轮次
     */
    public CodeTaskResult execute(CodeTaskContext context, VirtualFileSystem vfs,
                                  String requirement, int maxRepairRounds) {
        CodeException.requireNonNull(context, "context");
        if (!context.canWrite()) {
            throw new CodeException(CodeErrorCode.OPERATION_NOT_ALLOWED,
                    "当前用户 " + context.userId() + " 没有写权限，无法执行编码任务");
        }
        TaskTrace trace = TaskTrace.start(context.traceId(), context.taskId().value());
        BudgetGuard guard = BudgetGuard.of(budget);
        RepoSnapshot before = vfs.snapshot();

        // ---------- 1. 上下文选择 ----------
        ContextSelector.SelectionResult selection = trace.timed("上下文选择",
                () -> new ContextSelector(vfs.snapshot().files())
                        .select(requirement, ContextBudget.of32k()));

        // ---------- 2. 提示词装配 ----------
        GenerationRequest request = trace.timed("提示词装配",
                () -> assembler.assemble(requirement, selection, 4096));

        // ---------- 3. 模型调用（带预算） ----------
        GenerationOutcome outcome;
        long t0 = System.currentTimeMillis();
        try {
            guard.acquireModelCall(request.estimateInputTokens());
            outcome = model.generate(request);
            guard.settleTokens(outcome.totalTokens());
            trace.step("模型调用", AgentStep.Status.SUCCESS,
                    System.currentTimeMillis() - t0, outcome.totalTokens(),
                    "model=" + outcome.modelId());
        } catch (CodeException e) {
            trace.step("模型调用", AgentStep.Status.FAILED,
                    System.currentTimeMillis() - t0, 0, e.getMessage());
            return finish(context, trace, List.of(), before, vfs, null,
                    CodeTaskResult.Status.BUDGET_EXCEEDED, null, 0, e.getMessage());
        }

        // ---------- 4. 解析补丁 ----------
        PatchParser.ParsedPatches parsed;
        try {
            parsed = PatchParser.parse(outcome.text(), null);
        } catch (CodeException e) {
            trace.step("补丁解析", AgentStep.Status.FAILED, 0, 0, e.getMessage());
            return finish(context, trace, List.of(), before, vfs, null,
                    CodeTaskResult.Status.UNPARSEABLE, null, 0, e.getMessage());
        }

        // ---------- 5. 安全审查（在应用之前！） ----------
        List<String> safetyRejections = new ArrayList<>();
        List<TextPatch> safePatches = new ArrayList<>();
        for (TextPatch patch : parsed.patches()) {
            var verdict = safety.check(patch);
            if (verdict.rejected()) {
                safetyRejections.add(patch.filePath() + "：" + String.join("；", verdict.reasons()));
                continue;
            }
            if (!secretScanner.scan(patch.filePath(),
                    patch.replace() == null ? "" : patch.replace()).isEmpty()) {
                safetyRejections.add(patch.filePath() + "：疑似包含密钥");
                continue;
            }
            safePatches.add(patch);
        }
        if (!safetyRejections.isEmpty() && safePatches.isEmpty()) {
            trace.step("安全审查", AgentStep.Status.FAILED, 0, 0,
                    String.join("；", safetyRejections));
            return finish(context, trace, List.of(), before, vfs, null,
                    CodeTaskResult.Status.REJECTED_BY_SAFETY, null, 0,
                    String.join("；", safetyRejections));
        }
        trace.step("安全审查",
                safetyRejections.isEmpty() ? AgentStep.Status.SUCCESS : AgentStep.Status.DEGRADED,
                0, 0, safePatches.size() + " 个通过，"
                        + safetyRejections.size() + " 个被拒");

        // ---------- 6. 应用补丁 ----------
        long t1 = System.currentTimeMillis();
        List<TextPatch> appliedPatches = new ArrayList<>();
        for (TextPatch patch : safePatches) {
            if (applier.applyTo(vfs, patch).success()) {
                appliedPatches.add(patch);
            }
        }
        trace.step("补丁应用",
                appliedPatches.isEmpty() ? AgentStep.Status.FAILED : AgentStep.Status.SUCCESS,
                System.currentTimeMillis() - t1, 0,
                appliedPatches.size() + "/" + safePatches.size());
        if (appliedPatches.isEmpty()) {
            return finish(context, trace, List.of(), before, vfs, null,
                    CodeTaskResult.Status.PATCH_FAILED, null, 0, "补丁全部未能应用");
        }

        // ---------- 7. 编译 + 自修复 ----------
        Path classes = createTempDir();
        SelfRepairLoop loop = new SelfRepairLoop(verifier, applier);
        SelfRepairLoop.RepairOutcome repair = loop.execute(vfs, classes,
                modelFixGenerator(request, guard), maxRepairRounds, true);
        trace.step("编译+自修复",
                repair.success() ? AgentStep.Status.SUCCESS : AgentStep.Status.FAILED,
                repair.elapsedMs(), 0, repair.summary() + "（修复 " + repair.rounds() + " 轮）");

        if (!repair.success()) {
            return finish(context, trace, appliedPatches, before, vfs, repair.finalResult(),
                    CodeTaskResult.Status.COMPILE_FAILED, null, repair.rounds(), repair.summary());
        }

        // ---------- 8. 评审 ----------
        RepoSnapshot after = vfs.snapshot();
        List<com.zero.ai.agentstudy.day17codeagent.review.ReviewComment> comments =
                new ArrayList<>();
        after.paths().forEach(path -> {
            String oldContent = before.get(path);
            String newContent = after.get(path);
            if (oldContent != null && newContent != null && !oldContent.equals(newContent)) {
                comments.addAll(reviewer.review(path, oldContent, newContent));
            }
        });
        trace.step("规则评审", AgentStep.Status.SUCCESS, 0, 0, comments.size() + " 条意见");

        return finish(context, trace, appliedPatches, before, vfs, repair.finalResult(),
                CodeTaskResult.Status.SUCCEEDED, List.copyOf(comments), repair.rounds(), null);
    }

    /**
     * 收尾：生成变更集 + PR 草稿，并 Trace 一步。
     */
    private CodeTaskResult finish(CodeTaskContext context, TaskTrace trace,
                                  List<TextPatch> appliedPatches, RepoSnapshot before,
                                  VirtualFileSystem vfs,
                                  CompileVerifier.CompilationResult compileResult,
                                  CodeTaskResult.Status status,
                                  List<com.zero.ai.agentstudy.day17codeagent.review.ReviewComment> comments,
                                  int repairRounds,
                                  String message) {
        RepoSnapshot after = vfs.snapshot();
        ChangeBundle bundle = ChangeBundle.from(before, after);
        PullRequestDraft draft = null;
        if (!appliedPatches.isEmpty()) {
            draft = PrDraftGenerator.generate(
                    "（由 CodeTaskContext " + context.taskId() + " 提交的需求）",
                    appliedPatches, bundle,
                    compileResult != null && compileResult.success(), null,
                    comments == null ? List.of() : comments);
        }
        trace.step("变更集+PR", AgentStep.Status.SUCCESS, 0, 0, bundle.toString());
        return new CodeTaskResult(context.taskId().value(), trace.traceId(), status,
                appliedPatches.size(), bundle, compileResult, null,
                comments == null ? List.of() : comments, draft, trace,
                repairRounds, message);
    }

    private FixGenerator modelFixGenerator(GenerationRequest original, BudgetGuard guard) {
        return (feedback, round) -> {
            guard.acquireRepairRound();
            guard.acquireModelCall(original.estimateInputTokens());
            GenerationOutcome again = model.generate(original);
            guard.settleTokens(again.totalTokens());
            try {
                return PatchParser.parse(again.text(), null).patches();
            } catch (RuntimeException e) {
                return List.of();
            }
        };
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("day17-pipeline-classes");
        } catch (Exception e) {
            throw CodeException.internal("无法创建编译输出目录", e);
        }
    }

    /** 便捷构造：给定租户与仓库直接跑。 */
    public static CodeTaskResult run(VirtualFileSystem vfs, CodeModel model, String requirement) {
        CodeTaskContext context = CodeTaskContext.of(
                com.zero.ai.agentstudy.day17codeagent.core.model.TenantId.of("tenant-acme"),
                RepoId.of("order-service"),
                com.zero.ai.agentstudy.day17codeagent.core.model.UserId.of("zhangsan"),
                java.util.Set.of("CODE_WRITER", "CODE_EXECUTOR"),
                TaskId.of("task-" + System.currentTimeMillis() % 10000));
        return new DefaultCodeAgentPipeline(model, TaskBudget.defaults())
                .execute(context, vfs, requirement, 3);
    }
}

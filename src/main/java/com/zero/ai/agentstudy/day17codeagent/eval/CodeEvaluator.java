package com.zero.ai.agentstudy.day17codeagent.eval;

import com.zero.ai.agentstudy.day17codeagent.context.ContextBudget;
import com.zero.ai.agentstudy.day17codeagent.context.ContextSelector;
import com.zero.ai.agentstudy.day17codeagent.generator.CodeModel;
import com.zero.ai.agentstudy.day17codeagent.generator.GenerationOutcome;
import com.zero.ai.agentstudy.day17codeagent.generator.GenerationRequest;
import com.zero.ai.agentstudy.day17codeagent.generator.PromptAssembler;
import com.zero.ai.agentstudy.day17codeagent.iterate.FixGenerator;
import com.zero.ai.agentstudy.day17codeagent.iterate.SelfRepairLoop;
import com.zero.ai.agentstudy.day17codeagent.patch.PatchApplier;
import com.zero.ai.agentstudy.day17codeagent.patch.PatchParser;
import com.zero.ai.agentstudy.day17codeagent.patch.TextPatch;
import com.zero.ai.agentstudy.day17codeagent.repo.SampleRepository;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;
import com.zero.ai.agentstudy.day17codeagent.sandbox.ProcessSandbox;
import com.zero.ai.agentstudy.day17codeagent.sandbox.SandboxPolicy;
import com.zero.ai.agentstudy.day17codeagent.verify.CompileVerifier;
import com.zero.ai.agentstudy.day17codeagent.verify.TestVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Code Agent 评测器：把整条链路跑一遍并产出 {@link TaskOutcome}。
 *
 * <p>它串联了前面所有章节的能力：</p>
 * <pre>
 *   选上下文 → 装配提示词 → 调模型 → 解析补丁 → 应用
 *      → 编译 → 自修复 → 跑测试 → 出结论
 * </pre>
 *
 * <p>有了它，「换个提示词到底有没有变好」这个问题才有答案，而不是靠感觉。</p>
 *
 * @author ZeroAi
 */
public class CodeEvaluator {

    private final CodeModel model;
    private final PromptAssembler assembler;
    private final PatchApplier applier;

    public CodeEvaluator(CodeModel model) {
        this(model, PromptAssembler.withRepoMap(), PatchApplier.tolerant());
    }

    public CodeEvaluator(CodeModel model, PromptAssembler assembler, PatchApplier applier) {
        this.model = model;
        this.assembler = assembler;
        this.applier = applier;
    }

    /**
     * 跑一个任务。
     *
     * @param taskId       任务 ID
     * @param requirement  自然语言需求
     * @param testClassName 用于验收的测试类名（可为 null 表示只做编译验收）
     * @param maxRepairRounds 自修复最大轮次
     */
    public TaskOutcome runOnce(String taskId, String requirement,
                               String testClassName, int maxRepairRounds) {
        long start = System.currentTimeMillis();
        int tokens = 0;
        String failureReason = null;
        int patchesTotal = 0;
        int patchesApplied = 0;
        boolean compiled = false;
        boolean testPassed = false;
        int rounds = 0;

        try {
            VirtualFileSystem vfs = VirtualFileSystem.temp("eval-" + taskId);
            vfs.writeAll(SampleRepository.files());

            // ---- 1. 上下文 + 提示词 ----
            ContextSelector selector = new ContextSelector(vfs.snapshot().files());
            ContextSelector.SelectionResult selection =
                    selector.select(requirement, ContextBudget.of32k());
            GenerationRequest request = assembler.assemble(requirement, selection, 4096);

            // ---- 2. 调模型 ----
            GenerationOutcome outcome = model.generate(request);
            tokens += outcome.totalTokens();

            // ---- 3. 解析 + 应用补丁 ----
            PatchParser.ParsedPatches parsed;
            try {
                parsed = PatchParser.parse(outcome.text(), null);
            } catch (RuntimeException e) {
                return new TaskOutcome(taskId, model.modelId(), 0, 0, false, false,
                        0, tokens, System.currentTimeMillis() - start,
                        "输出无法解析为补丁");
            }
            patchesTotal = parsed.patches().size();
            for (TextPatch patch : parsed.patches()) {
                if (applier.applyTo(vfs, patch).success()) {
                    patchesApplied++;
                }
            }
            if (patchesApplied == 0) {
                failureReason = "补丁一个都没打上";
            }

            // ---- 4. 编译 + 自修复 ----
            Path classes = Files.createTempDirectory("day17-eval-classes");
            CompileVerifier verifier = CompileVerifier.defaultVerifier();
            SelfRepairLoop loop = new SelfRepairLoop(verifier, applier);
            SelfRepairLoop.RepairOutcome repair = loop.execute(
                    vfs, classes,
                    modelFixGenerator(request, model), maxRepairRounds, true);
            rounds = repair.rounds();
            compiled = repair.success();
            if (!compiled && failureReason == null) {
                failureReason = "编译未通过（" + repair.stopReason() + "）";
            }

            // ---- 5. 跑测试 ----
            if (compiled && testClassName != null) {
                Path classes2 = Files.createTempDirectory("day17-eval-classes2");
                verifier.compile(vfs, classes2);
                Path sandboxRoot = Files.createTempDirectory("day17-eval-sandbox");
                TestVerifier testVerifier = new TestVerifier(
                        new ProcessSandbox(SandboxPolicy.runtimeOnly(), sandboxRoot));
                var suite = testVerifier.runAll(classes2, List.of(testClassName));
                testPassed = suite.allPassed();
                if (!testPassed && failureReason == null) {
                    failureReason = "测试未通过";
                }
            } else if (compiled) {
                testPassed = true; // 没有测试时以编译通过为准
            }
        } catch (Exception e) {
            failureReason = "执行异常：" + e.getClass().getSimpleName();
        }

        return new TaskOutcome(taskId, model.modelId(), patchesTotal, patchesApplied,
                compiled, testPassed, rounds, tokens,
                System.currentTimeMillis() - start, failureReason);
    }

    /**
     * 把「再问模型一次」包装成 {@link FixGenerator}。
     *
     * <p>真实场景里应当在反馈里带上编译错误；这里因为用了桩模型，
     * 重点在于验证「循环能转起来」，所以复用原始请求即可。</p>
     */
    private FixGenerator modelFixGenerator(GenerationRequest original, CodeModel model) {
        return (feedback, round) -> {
            GenerationOutcome again = model.generate(original);
            try {
                return PatchParser.parse(again.text(), null).patches();
            } catch (RuntimeException e) {
                return List.of();
            }
        };
    }

    /** 批量跑多个任务。 */
    public EvalReport runSuite(List<String[]> tasks, String testClassName, int maxRepairRounds) {
        List<TaskOutcome> outcomes = new ArrayList<>();
        for (String[] task : tasks) {
            outcomes.add(runOnce(task[0], task[1], testClassName, maxRepairRounds));
        }
        return new EvalReport(List.copyOf(outcomes));
    }
}

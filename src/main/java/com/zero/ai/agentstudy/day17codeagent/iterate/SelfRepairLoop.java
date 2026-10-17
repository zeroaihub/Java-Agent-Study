package com.zero.ai.agentstudy.day17codeagent.iterate;

import com.zero.ai.agentstudy.day17codeagent.patch.PatchApplier;
import com.zero.ai.agentstudy.day17codeagent.patch.PatchResult;
import com.zero.ai.agentstudy.day17codeagent.patch.TextPatch;
import com.zero.ai.agentstudy.day17codeagent.repo.RepoSnapshot;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;
import com.zero.ai.agentstudy.day17codeagent.verify.CompileVerifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 自修复循环（Self-Repair Loop）：Code Agent 的核心能力。
 *
 * <h3>它到底是什么</h3>
 * <p>一句话：<b>把失败结果重新喂给模型，让它改到对为止</b>。
 * 形式上是 ReAct 的一个特例：</p>
 * <pre>
 *   编译（Observe） → 结构化为诊断（Reason） → 生成修复补丁（Act） → 再编译
 * </pre>
 *
 * <h3>三个必须有的刹车，缺一不可</h3>
 * <ol>
 *     <li><b>轮次上限</b>（{@code maxAttempts}）：没有上限就是潜在的钱包黑洞；</li>
 *     <li><b>无进展检测</b>（{@code stopOnNoProgress}）：连着两轮产出完全相同的错误集合，
 *         说明模型没听懂反馈，继续重试毫无意义，应立即停；</li>
 *     <li><b>内容指纹比对</b>：如果改完之后仓库指纹没变，说明补丁根本没落地，
 *         同样属于「无效努力」。</li>
 * </ol>
 *
 * <p>这三条不是「锦上添花」——Demo 里一堆 random retry 看着很聪明，上线之后
 * 就是一个晚上烧掉几百美元然后告诉你「任务失败」。</p>
 *
 * @author ZeroAi
 */
public class SelfRepairLoop {

    /** 默认最大轮次。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final CompileVerifier verifier;
    private final PatchApplier applier;

    public SelfRepairLoop(CompileVerifier verifier, PatchApplier applier) {
        this.verifier = verifier;
        this.applier = applier;
    }

    public static SelfRepairLoop create() {
        return new SelfRepairLoop(CompileVerifier.defaultVerifier(), PatchApplier.tolerant());
    }

    /**
     * 执行自修复。
     *
     * @param vfs        代码仓库
     * @param outputDir  class 输出目录
     * @param generator  修复补丁生成器
     * @param maxAttempts 最大轮次
     * @param stopOnNoProgress 是否在「无进展」时提前停止
     */
    public RepairOutcome execute(VirtualFileSystem vfs, Path outputDir,
                                 FixGenerator generator,
                                 int maxAttempts, boolean stopOnNoProgress) {
        long start = System.currentTimeMillis();
        List<RepairAttempt> attempts = new ArrayList<>();

        CompileVerifier.CompilationResult current = verifier.compile(vfs, outputDir);
        if (current.success()) {
            return new RepairOutcome(true, 0, List.of(), StopReason.ALREADY_VALID,
                    System.currentTimeMillis() - start, current, "初始状态即编译通过，无需修复");
        }

        String previousSignatures = String.join("|", current.errorSignatures());

        for (int round = 1; round <= maxAttempts; round++) {
            long roundStart = System.currentTimeMillis();
            String feedback = current.feedbackForModel();
            var before = current.errors();

            List<TextPatch> fixes = generator.generateFixes(feedback, round);
            if (fixes == null || fixes.isEmpty()) {
                attempts.add(new RepairAttempt(round, feedback, 0, before, before,
                        false, System.currentTimeMillis() - roundStart, true));
                return new RepairOutcome(false, round, List.copyOf(attempts),
                        StopReason.NO_FIX_GENERATED, System.currentTimeMillis() - start,
                        current, "模型放弃了修复，不再重试");
            }

            RepoSnapshot beforeSnapshot = vfs.snapshot();
            int applied = 0;
            for (TextPatch fix : fixes) {
                PatchResult result = applier.applyTo(vfs, fix);
                if (result.success()) {
                    applied++;
                }
            }
            RepoSnapshot afterSnapshot = vfs.snapshot();

            // 刹车 3：内容没变 = 补丁根本没生效
            boolean noop = beforeSnapshot.sameAs(afterSnapshot);
            if (noop && stopOnNoProgress) {
                attempts.add(new RepairAttempt(round, feedback, applied, before, before,
                        false, System.currentTimeMillis() - roundStart, true));
                return new RepairOutcome(false, round, List.copyOf(attempts),
                        StopReason.NO_PROGRESS, System.currentTimeMillis() - start,
                        current, "补丁应用后仓库内容没有任何变化（请检查 SEARCH 片段是否来自当前文件内容）");
            }

            current = verifier.compile(vfs, outputDir);
            var afterErrors = current.errors();
            attempts.add(new RepairAttempt(round, feedback, applied, before, afterErrors,
                    current.success(), System.currentTimeMillis() - roundStart, noop));

            if (current.success()) {
                return new RepairOutcome(true, round, List.copyOf(attempts),
                        StopReason.CONVERGED, System.currentTimeMillis() - start,
                        current, "第 " + round + " 轮修复成功");
            }

            // 刹车 2：错误集合完全没变 = 模型没听懂
            String nowSignatures = String.join("|", current.errorSignatures());
            if (stopOnNoProgress && Objects.equals(previousSignatures, nowSignatures)) {
                return new RepairOutcome(false, round, List.copyOf(attempts),
                        StopReason.NO_PROGRESS, System.currentTimeMillis() - start,
                        current, "连续两轮产生完全相同的错误集合，继续重试没有意义");
            }
            previousSignatures = nowSignatures;
        }

        // 刹车 1：轮次上限
        return new RepairOutcome(false, maxAttempts, List.copyOf(attempts),
                StopReason.ATTEMPTS_EXHAUSTED, System.currentTimeMillis() - start,
                current, "已达最大重试轮次 " + maxAttempts + "，仍未通过编译");
    }

    /** 停止原因。 */
    public enum StopReason {
        /** 一开始就合法。 */
        ALREADY_VALID,
        /** 修复成功。 */
        CONVERGED,
        /** 轮次用尽。 */
        ATTEMPTS_EXHAUSTED,
        /** 检测到无进展。 */
        NO_PROGRESS,
        /** 模型未提供任何补丁。 */
        NO_FIX_GENERATED
    }

    /**
     * 自修复结果。
     *
     * @param success    最终是否编译通过
     * @param rounds     实际执行轮次
     * @param attempts   每轮记录
     * @param stopReason 停止原因
     * @param elapsedMs  总耗时
     * @param finalResult 最后一次编译结果
     * @param summary    一句话总结
     */
    public record RepairOutcome(
            boolean success,
            int rounds,
            List<RepairAttempt> attempts,
            StopReason stopReason,
            long elapsedMs,
            CompileVerifier.CompilationResult finalResult,
            String summary
    ) {
        @Override
        public String toString() {
            return String.format("RepairOutcome{成功=%s, 轮次=%d, 停止原因=%s, %dms} %s",
                    success, rounds, stopReason, elapsedMs, summary);
        }
    }
}

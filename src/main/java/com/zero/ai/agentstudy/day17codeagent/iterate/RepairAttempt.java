package com.zero.ai.agentstudy.day17codeagent.iterate;

import com.zero.ai.agentstudy.day17codeagent.verify.Diagnostic;

import java.util.List;

/**
 * 自修复循环的单次尝试记录。
 *
 * <p>每一轮都要留下「我改了什么、改完变成什么样」的完整证据。
 * 没有这份记录，出问题时你只能看到「第 3 轮失败了」，完全不知道前面两轮干了什么。</p>
 *
 * @param round         轮次（从 1 开始）
 * @param feedback      本轮喂给模型的反馈
 * @param patchCount    本轮应用的补丁数
 * @param diagnosticsBefore 修改前的编译错误
 * @param diagnosticsAfter  修改后的编译错误
 * @param compiled      本轮修改后是否编译通过
 * @param elapsedMs     本轮耗时
 * @param noop          本轮是否「看起来在忙但其实没改任何东西」
 * @author ZeroAi
 */
public record RepairAttempt(
        int round,
        String feedback,
        int patchCount,
        List<Diagnostic> diagnosticsBefore,
        List<Diagnostic> diagnosticsAfter,
        boolean compiled,
        long elapsedMs,
        boolean noop
) {

    /** 本轮修复掉的错误数。 */
    public int fixedCount() {
        int before = diagnosticsBefore.size();
        int after = diagnosticsAfter.size();
        return Math.max(0, before - after);
    }

    /** 本轮是否引入了新错误。 */
    public boolean introducedNewErrors() {
        return diagnosticsAfter.size() > diagnosticsBefore.size();
    }

    @Override
    public String toString() {
        return String.format("第 %d 轮：%d 个补丁, 错误 %d → %d, 编译%s, %dms%s",
                round, patchCount,
                diagnosticsBefore.size(), diagnosticsAfter.size(),
                compiled ? "通过" : "失败", elapsedMs,
                noop ? "（无实质改动）" : "");
    }
}

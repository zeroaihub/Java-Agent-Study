package com.zero.ai.agentstudy.day17codeagent.generator;

import com.zero.ai.agentstudy.day17codeagent.context.ContextBudget;
import com.zero.ai.agentstudy.day17codeagent.context.ContextSelector;
import com.zero.ai.agentstudy.day17codeagent.patch.PatchParser;
import com.zero.ai.agentstudy.day17codeagent.patch.UnifiedDiff;
import com.zero.ai.agentstudy.day17codeagent.patch.PatchApplier;
import com.zero.ai.agentstudy.day17codeagent.patch.PatchResult;
import com.zero.ai.agentstudy.day17codeagent.patch.TextPatch;
import com.zero.ai.agentstudy.day17codeagent.repo.SampleRepository;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;

import java.util.List;

/**
 * Chapter 09 演示：代码生成策略。
 *
 * <p>跑的是一条真实的「选择上下文 → 装配提示词 → 调模型 → 解析补丁 → 落盘 → 出 diff」链路。</p>
 *
 * @author ZeroAi
 */
public class Chapter09Demo {

    public static void main(String[] args) {
        VirtualFileSystem vfs = VirtualFileSystem.temp("generator-demo");
        vfs.writeAll(SampleRepository.files());
        var sources = vfs.snapshot().files();

        String requirement = "请给 Money 增加 multiply 方法，支持乘以一个整数倍率";

        // ---- 1. 选上下文 ----
        ContextSelector selector = new ContextSelector(sources);
        var selection = selector.select(requirement, ContextBudget.of32k());
        System.out.println("=== [1] 上下文选择 ===");
        System.out.println("  " + selection);

        // ---- 2. 装配提示词 ----
        PromptAssembler assembler = PromptAssembler.withRepoMap();
        GenerationRequest request = assembler.assemble(requirement, selection, 4096);
        System.out.println("\n=== [2] 提示词装配 ===");
        System.out.println("  系统提示词 = " + PromptAssembler.SYSTEM_PROMPT.lines().count()
                + " 行 / " + request.systemPrompt().length() + " 字符");
        System.out.println("  用户提示词 = " + request.userPrompt().lines().count()
                + " 行 / " + request.userPrompt().length() + " 字符");
        System.out.println("  估算输入 token = " + request.estimateInputTokens()
                + "  temperature = " + request.temperature());

        // ---- 3. 调用模型 ----
        CodeModel model = StubCodeModel.reliable();
        GenerationOutcome outcome = model.generate(request);
        System.out.println("\n=== [3] 模型输出 ===");
        System.out.println("  " + outcome);
        System.out.println("  --- 原文 ---");
        outcome.text().lines().forEach(l -> System.out.println("    " + l));

        // ---- 4. 解析 + 应用 ----
        System.out.println("\n=== [4] 解析补丁并应用 ===");
        var parsed = PatchParser.parse(outcome.text(), null);
        PatchApplier applier = PatchApplier.strict();
        for (TextPatch patch : parsed.patches()) {
            System.out.println("  " + patch);
            PatchResult result = applier.applyTo(vfs, patch);
            System.out.println("  → " + result);
        }

        System.out.println("\n=== [5] 最终 diff（人审阅用）===");
        System.out.println(UnifiedDiff.generateCompact(SampleRepository.MONEY,
                SampleRepository.files().get(SampleRepository.MONEY),
                vfs.read(SampleRepository.MONEY).content()));

        // ---- 6. 两种策略的成本对比 ----
        System.out.println("=== [6] 整文件重写 vs SEARCH/REPLACE ===");
        var compare = PromptAssembler.compareRewriteVsPatch(
                List.of(SampleRepository.MONEY),
                sources,
                parsed.patches().stream().map(p -> p.search() + "\n" + p.replace()).toList());
        System.out.println("  " + compare);
        System.out.println("  → 这还只是输出 token。整文件重写真正的风险是模型「顺手」改动无关代码。");

        // ---- 7. 会犯错的桩：为 Chapter 13 的自修复循环埋伏笔 ----
        System.out.println("\n=== [7] 会犯错的模型（第一次输出缺一个分号）===");
        CodeModel flaky = StubCodeModel.flaky();
        GenerationOutcome first = flaky.generate(request);
        System.out.println("  第一次：" + first);
        boolean missingSemicolon = first.text().contains("multiply(BigDecimal.valueOf(factor)))");
        System.out.println("  第一次输出故意缺分号 = " + missingSemicolon);
        GenerationOutcome second = flaky.generate(request);
        System.out.println("  第二次：" + second);
        System.out.println("  → Chapter 11 会真的编译它，Chapter 13 会把编译错误喂回去让它修好");
    }
}

package com.zero.ai.agentstudy.day17codeagent.patch;

import com.zero.ai.agentstudy.day17codeagent.repo.SampleRepository;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;

/**
 * Chapter 07 演示：补丁表示与应用。
 *
 * <p>运行可见一条核心主张：<b>打不上的补丁必须明确失败，绝不猜着改。</b></p>
 *
 * <ol>
 *     <li>精确匹配 → 成功；</li>
 *     <li>上下文对不上 → 明确拒绝，并给出可执行的修正建议；</li>
 *     <li>多处命中 → 拒绝（即使内容合法），宁可让模型多带几行上下文；</li>
 *     <li>指定 occurrence / replaceAll → 可控地放开；</li>
 *     <li>容错模式会记录实际使用的策略，并要求人工复核。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class Chapter07Demo {

    public static void main(String[] args) {
        VirtualFileSystem vfs = VirtualFileSystem.temp("patch-demo");
        vfs.writeAll(SampleRepository.files());
        String original = vfs.read(SampleRepository.MONEY).content();

        // ---- 1. 精确匹配成功 ----
        System.out.println("=== [1] 精确匹配：给 Money 增加 multiply 方法 ===");
        TextPatch addMultiply = TextPatch.unique(
                SampleRepository.MONEY,
                "    public Money add(Money other) {\n"
                        + "        return new Money(this.amount.add(other.amount));\n"
                        + "    }",
                "    public Money add(Money other) {\n"
                        + "        return new Money(this.amount.add(other.amount));\n"
                        + "    }\n\n"
                        + "    public Money multiply(long factor) {\n"
                        + "        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)));\n"
                        + "    }");
        show(PatchApplier.strict().apply(original, addMultiply));

        // ---- 2. 上下文不匹配 → 必须拒绝 ----
        System.out.println("\n=== [2] 上下文不匹配（模型凭记忆写的补丁）===");
        TextPatch stale = TextPatch.unique(
                SampleRepository.MONEY,
                "    public Money add(Money other) {\n"
                        + "        return new Money(amount.add(other.amount));   // 少了 this.\n"
                        + "    }",
                "    public Money add(Money other) {\n"
                        + "        throw new UnsupportedOperationException();\n"
                        + "    }");
        show(PatchApplier.strict().apply(original, stale));
        System.out.println("  → 若这里「聪明地」退化成近似替换，代码会被静默改坏且很难发现");

        // ---- 3. 多处命中 → 拒绝 ----
        System.out.println("\n=== [3] 多处命中：目标不唯一 ===");
        TextPatch ambiguous = TextPatch.unique(
                SampleRepository.MONEY,
                "        return new Money",
                "        return build");
        show(PatchApplier.strict().apply(original, ambiguous));

        // ---- 4. 指定 occurrence 后可控放开 ----
        System.out.println("\n=== [4] 指定 occurrence=2：只改第二处 ===");
        show(PatchApplier.strict().apply(original, TextPatch.nth(SampleRepository.MONEY,
                "        return new Money", "        return build", 2)));

        System.out.println("\n=== [5] replaceAll：全部替换 ===");
        show(PatchApplier.strict().apply(original, TextPatch.all(SampleRepository.MONEY,
                "new Money", "Money.from")));

        // ---- 6. occurrence 越界 ----
        System.out.println("\n=== [6] occurrence 越界 ===");
        show(PatchApplier.strict().apply(original, TextPatch.nth(SampleRepository.MONEY,
                "        return new Money", "        return build", 99)));

        // ---- 7. 容错模式：缩进不一致也能改，但必须标注 ----
        System.out.println("\n=== [7] 容错模式：模型丢掉了缩进 ===");
        TextPatch lostIndent = TextPatch.unique(
                SampleRepository.MONEY,
                "public static Money zero() {\nreturn Money.of(0);\n}",
                "public static Money zero() {\nreturn Money.of(1);\n}");
        PatchResult fuzzy = PatchApplier.tolerant()
                .apply(original.replace(
                        "    public static Money zero() {\n        return new Money(BigDecimal.ZERO);\n    }",
                        "    public static Money zero() {\n        return Money.of(0);\n    }"),
                        lostIndent);
        show(fuzzy);
        System.out.println("  是否用了有损容错 = " + fuzzy.usedFuzzyMatch()
                + "（为 true 时评审环节应要求人工复核）");

        // ---- 8. 真正落到 VFS 并生成 diff ----
        System.out.println("\n=== [8] 落到 VFS 并生成审阅用 diff ===");
        PatchResult applied = PatchApplier.strict().applyTo(vfs, addMultiply);
        show(applied);
        String after = vfs.read(SampleRepository.MONEY).content();
        System.out.println(UnifiedDiff.generateCompact(SampleRepository.MONEY, original, after));
        System.out.println("  统计 = " + UnifiedDiff.stat(original, after));
    }

    private static void show(PatchResult result) {
        System.out.println("  " + result);
        if (result.success()) {
            System.out.println("    替换后片段：");
            if (result.content() != null) {
                result.content().lines()
                        .filter(l -> l.contains("multiply") || l.contains("build") || l.contains("Money.from"))
                        .forEach(l -> System.out.println("      " + l));
            }
        } else if (result.hint() != null) {
            System.out.println("    建议 → " + result.hint());
        }
    }
}

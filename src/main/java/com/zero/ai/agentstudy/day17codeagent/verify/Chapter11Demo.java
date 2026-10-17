package com.zero.ai.agentstudy.day17codeagent.verify;

import com.zero.ai.agentstudy.day17codeagent.patch.PatchApplier;
import com.zero.ai.agentstudy.day17codeagent.patch.PatchResult;
import com.zero.ai.agentstudy.day17codeagent.patch.TextPatch;
import com.zero.ai.agentstudy.day17codeagent.repo.SampleRepository;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Chapter 11 演示：编译验证。
 *
 * <p>这里<b>真的调用 javac</b>。跑出错 Hewish 的诊断是结构化的（文件 / 行 / 列 / 诊断码），
 * 这正是自修复循环能够工作的基础。</p>
 *
 * @author ZeroAi
 */
public class Chapter11Demo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== [0] 运行环境自检 ===");
        System.out.println("  JavaCompiler 可用 = " + CompileVerifier.available());
        if (!CompileVerifier.available()) {
            System.out.println("  必须运行在 JDK 上：只有 JDK 才有 javax.tools.JavaCompiler");
            return;
        }

        VirtualFileSystem vfs = VirtualFileSystem.temp("compile-baseline");
        vfs.writeAll(SampleRepository.files());
        CompileVerifier verifier = CompileVerifier.defaultVerifier();

        // ---- 1. 基线：原仓库必须能通过编译 ----
        System.out.println("\n=== [1] 基线编译（未修改的仓库）===");
        Path out1 = Files.createTempDirectory("day17-classes-baseline");
        CompileVerifier.CompilationResult baseline = verifier.compile(vfs, out1);
        System.out.println("  " + baseline);
        System.out.println("  编译产物：" + vfs.listFilesEndingWith("", ".java").size()
                + " 个源文件 → " + out1 + " 下的 class");

        // ---- 2. 一个「看似正确」实则编译不过的补丁 ----
        System.out.println("\n=== [2] 打上一个缺分号的补丁 ===");
        TextPatch brokenPatch = TextPatch.unique(
                SampleRepository.MONEY,
                "    public Money add(Money other) {\n"
                        + "        return new Money(this.amount.add(other.amount));\n"
                        + "    }",
                "    public Money add(Money other) {\n"
                        + "        return new Money(this.amount.add(other.amount));\n"
                        + "    }\n\n"
                        + "    public Money multiply(long factor) {\n"
                        + "        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)))\n"
                        + "    }");
        PatchResult r1 = PatchApplier.strict().applyTo(vfs, brokenPatch);
        System.out.println("  应用补丁 → " + (r1.success() ? "成功" : "失败：" + r1.reason()));

        Path out2 = Files.createTempDirectory("day17-classes-broken");
        CompileVerifier.CompilationResult broken = verifier.compile(vfs, out2);
        System.out.println("  " + broken);
        System.out.println("  --- 结构化诊断 ---");
        broken.errors().forEach(d -> System.out.println("    " + d));
        System.out.println("  --- 喂给模型的反馈 ---");
        System.out.println(indentText(broken.feedbackForModel()));

        // ---- 3. 修好它 ----
        System.out.println("\n=== [3] 修好后重新编译 ===");
        TextPatch fix = TextPatch.unique(
                SampleRepository.MONEY,
                "        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)))\n",
                "        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)));\n");
        System.out.println("  " + PatchApplier.strict().applyTo(vfs, fix));

        Path out3 = Files.createTempDirectory("day17-classes-fixed");
        CompileVerifier.CompilationResult fixed = verifier.compile(vfs, out3);
        System.out.println("  " + fixed);
        System.out.println("  → 编译通过，且新增的 multiply 方法确实存在：");
        vfs.read(SampleRepository.MONEY).content().lines()
                .filter(l -> l.contains("multiply"))
                .forEach(l -> System.out.println("      " + l.strip()));

        // ---- 4. 内存片段编译：自修复循环里的低成本检查 ----
        System.out.println("\n=== [4] 内存片段编译（不落盘，快得多）===");
        String good = "public class Probe { public static void main(String[] a) { System.out.println(1); } }";
        System.out.println("  合法片段 → " + verifier.compileSnippet("Probe", good));

        String bad = "public class Probe { public static void main(String[] a) { System.out.println(1) } }";
        var badResult = verifier.compileSnippet("Probe", bad);
        System.out.println("  缺分号   → " + badResult);
        badResult.errors().forEach(d -> System.out.println("      " + d));

        long t0 = System.nanoTime();
        verifier.compileSnippet("Probe", good);
        long snippetMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("  单次片段编译耗时 ≈ %d ms（首次含类加载，后续更快）%n", snippetMs);

        // ---- 5. 错误签名：无进展检测的基础 ----
        System.out.println("\n=== [5] 错误签名（用于判断「两轮是否犯同一个错」）===");
        System.out.println("  " + badResult.errorSignatures());
        System.out.println("  若第二轮的错误签名集合与第一轮完全重合，"
                + "说明模型没听懂反馈 —— 应立即停止重试而不是烧到上限");
    }

    private static String indentText(String text) {
        StringBuilder sb = new StringBuilder();
        text.lines().forEach(l -> sb.append("      ").append(l).append('\n'));
        return sb.toString();
    }
}

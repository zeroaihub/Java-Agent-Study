package com.zero.ai.agentstudy.day17codeagent.iterate;

import com.zero.ai.agentstudy.day17codeagent.patch.PatchApplier;
import com.zero.ai.agentstudy.day17codeagent.patch.TextPatch;
import com.zero.ai.agentstudy.day17codeagent.repo.SampleRepository;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Chapter 13 演示：自修复循环。
 *
 * <p>四个场景全部基于<b>真实 javac 编译</b>：</p>
 * <ol>
 *     <li>一开始就是好的 → 不做无谓修复；</li>
 *     <li>一轮修好；</li>
 *     <li>模型没听懂反馈（每轮给出同样的补丁）→ <b>无进展检测提前喊停</b>；</li>
 *     <li>轮次用尽。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class Chapter13Demo {

    public static void main(String[] args) throws Exception {
        if (!com.zero.ai.agentstudy.day17codeagent.verify.CompileVerifier.available()) {
            System.out.println("需要 JDK 才能运行本章 Demo");
            return;
        }
        SelfRepairLoop loop = SelfRepairLoop.create();

        // ================= 场景 1：本来就编译得过 =================
        System.out.println("=== [1] 仓库本来就是好的 ===");
        VirtualFileSystem vfs1 = VirtualFileSystem.temp("repair-clean");
        vfs1.writeAll(SampleRepository.files());
        SelfRepairLoop.RepairOutcome outcome1 = loop.execute(vfs1,
                Files.createTempDirectory("r1"), (feedback, round) -> List.of(), 3, true);
        System.out.println("  " + outcome1);
        System.out.println("  → 一行都没重编译，直接返回。这是很多实现会漏掉的一步：");
        System.out.println("    明明已经通过了还要跑修复，白烧一次模型调用。");

        // ================= 场景 2：一轮修好 =================
        System.out.println("\n=== [2] 缺一个分号：一轮修好 ===");
        VirtualFileSystem vfs2 = VirtualFileSystem.temp("repair-once");
        vfs2.writeAll(SampleRepository.files());
        PatchApplier.strict().applyTo(vfs2, TextPatch.unique(
                SampleRepository.MONEY,
                "    public Money add(Money other) {\n"
                        + "        return new Money(this.amount.add(other.amount));\n"
                        + "    }",
                "    public Money add(Money other) {\n"
                        + "        return new Money(this.amount.add(other.amount));\n"
                        + "    }\n\n"
                        + "    public Money multiply(long factor) {\n"
                        + "        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)))\n"
                        + "    }"));

        // 这个生成器会「读懂」反馈里的行号，生成一个补分号的补丁
        SelfRepairLoop.RepairOutcome outcome2 = loop.execute(vfs2,
                Files.createTempDirectory("r2"), semicolonFixer(), 3, true);
        System.out.println("  " + outcome2);
        outcome2.attempts().forEach(a -> System.out.println("    " + a));

        // ================= 场景 3：无进展（模型每轮给出一样的错补丁）=================
        System.out.println("\n=== [3] 模型没听懂反馈：无进展检测 ===");
        VirtualFileSystem vfs3 = VirtualFileSystem.temp("repair-stuck");
        vfs3.writeAll(SampleRepository.files());
        PatchApplier.strict().applyTo(vfs3, TextPatch.unique(
                SampleRepository.MONEY,
                "    public Money add(Money other) {\n"
                        + "        return new Money(this.amount.add(other.amount));\n"
                        + "    }",
                "    public Money add(Money other) {\n"
                        + "        return new Money(this.amount.add(other.amount));\n"
                        + "    }\n\n"
                        + "    public Money multiply(long factor) {\n"
                        + "        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)))\n"
                        + "    }"));

        // 这个生成器无论反馈是什么，都坚持返回同一个「加了注释但没加引号分号」的补丁
        SelfRepairLoop.RepairOutcome outcome3 = loop.execute(vfs3,
                Files.createTempDirectory("r3"), stubbornGenerator(), 5, true);
        System.out.println("  " + outcome3);
        outcome3.attempts().forEach(a -> System.out.println("    " + a));
        System.out.println("  → 没有开启 stopOnNoProgress 时会发生什么？");

        VirtualFileSystem vfs3b = VirtualFileSystem.temp("repair-stuck-b");
        vfs3b.writeAll(SampleRepository.files());
        PatchApplier.strict().applyTo(vfs3b, TextPatch.unique(
                SampleRepository.MONEY,
                "    public Money add(Money other) {\n"
                        + "        return new Money(this.amount.add(other.amount));\n"
                        + "    }",
                "    public Money add(Money other) {\n"
                        + "        return new Money(this.amount.add(other.amount));\n"
                        + "    }\n\n"
                        + "    public Money multiply(long factor) {\n"
                        + "        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)))\n"
                        + "    }"));
        SelfRepairLoop.RepairOutcome outcome3b = loop.execute(vfs3b,
                Files.createTempDirectory("r3b"), stubbornGenerator(), 5, false);
        System.out.println("  关闭检测：轮次=" + outcome3b.rounds()
                + " 停止原因=" + outcome3b.stopReason()
                + " | 开启检测：轮次=" + outcome3.rounds()
                + " 停止原因=" + outcome3.stopReason());

        // ================= 场景 4：轮次用尽 =================
        System.out.println("\n=== [4] 错误多到一轮修不完：轮次用尽 ===");
        VirtualFileSystem vfs4 = VirtualFileSystem.temp("repair-exhaust");
        vfs4.writeAll(SampleRepository.files());
        vfs4.write("src/main/java/demo/order/Broken.java", """
                package demo.order;

                public class Broken {
                    void a() { int x = }
                    void b() { int y = }
                    void c() { int z = }
                }
                """);
        SelfRepairLoop.RepairOutcome outcome4 = loop.execute(vfs4,
                Files.createTempDirectory("r4"), hopelessGenerator(), 2, true);
        System.out.println("  " + outcome4);
        outcome4.attempts().forEach(a -> System.out.println("    " + a));
        System.out.println("  最后剩下的错误：");
        outcome4.finalResult().errors().forEach(d -> System.out.println("      " + d));
    }

    /** 会真正修复「缺少分号」的生成器：按反馈里的文件名与行号定位。 */
    private static FixGenerator semicolonFixer() {
        return (feedback, round) -> {
            System.out.println("    [模型收到反馈] " + feedback.replaceAll("\\s+", " ").strip());
            return List.of(TextPatch.unique(
                    SampleRepository.MONEY,
                    "        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)))\n",
                    "        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)));\n"));
        };
    }

    /** 顽固的生成器：不管反馈是什么，都返回一个「看起来改了其实没改」的补丁（给注释加字）。 */
    private static FixGenerator stubbornGenerator() {
        return (feedback, round) -> List.of(TextPatch.unique(
                SampleRepository.MONEY,
                "/**\n * 金额值对象：不可变，单位为元。\n */",
                "/**\n * 金额值对象：不可变，单位为元（第 " + round + " 次修订）。\n */"));
    }

    /**
     * 「每轮都在改，但每轮都改出新错误」的生成器。
     *
     * <p>它模拟了一类很常见的失败：模型确实在响应反馈，但每次都引入新的语法错误，
     * 错误集合始终在变（因此无进展检测不会触发），只能靠轮次上限兜底。</p>
     */
    private static FixGenerator hopelessGenerator() {
        return (feedback, round) -> List.of(TextPatch.unique(
                "src/main/java/demo/order/Broken.java",
                "public class Broken {",
                "public class Broken {\n    int attempt" + round + " = ;"));
    }
}

package com.zero.ai.agentstudy.day17codeagent.verify;

import com.zero.ai.agentstudy.day17codeagent.patch.PatchApplier;
import com.zero.ai.agentstudy.day17codeagent.patch.TextPatch;
import com.zero.ai.agentstudy.day17codeagent.repo.SampleRepository;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;
import com.zero.ai.agentstudy.day17codeagent.sandbox.ProcessSandbox;
import com.zero.ai.agentstudy.day17codeagent.sandbox.SandboxPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Chapter 12 演示：测试验证。
 *
 * <p>核心论点：<b>编译通过 ≠ 正确。</b>语法合法只说明机器能读懂，不说明行为符合预期。</p>
 *
 * <p>本章会真的编译一个测试类、真的运行它、真的看到断言失败。</p>
 *
 * @author ZeroAi
 */
public class Chapter12Demo {

    private static final String TEST_FILE = "src/test/java/demo/order/MoneyTest.java";

    public static void main(String[] args) throws Exception {
        VirtualFileSystem vfs = VirtualFileSystem.temp("test-demo");
        vfs.writeAll(SampleRepository.files());
        vfs.write(TEST_FILE, testSource());

        CompileVerifier verifier = CompileVerifier.defaultVerifier();
        Path classes = Files.createTempDirectory("day17-test-classes");
        Path sandboxRoot = Files.createTempDirectory("day17-test-sandbox");
        ProcessSandbox sandbox = new ProcessSandbox(SandboxPolicy.runtimeOnly(), sandboxRoot);
        TestVerifier testVerifier = new TestVerifier(sandbox);

        // ---- 1. 编译（含测试） ----
        System.out.println("=== [1] 编译主代码 + 测试代码 ===");
        CompileVerifier.CompilationResult compiled = verifier.compile(vfs, classes);
        System.out.println("  " + compiled);
        if (!compiled.success()) {
            compiled.errors().forEach(d -> System.out.println("    " + d));
            return;
        }

        // ---- 2. 运行测试：全绿 ----
        System.out.println("\n=== [2] 运行测试（原实现）===");
        var suite = testVerifier.runAll(classes, List.of("demo.order.MoneyTest"));
        System.out.println("  " + suite);
        suite.results().forEach(r -> {
            System.out.println("    " + r);
            r.output().lines().filter(l -> l.contains("PASS:") || l.contains("FAIL:"))
                    .forEach(l -> System.out.println("        " + l.strip()));
        });

        // ---- 3. 引入一个「能编译但行为错误」的改动 ----
        System.out.println("\n=== [3] 引入逻辑错误：Money.zero() 返回 1 而不是 0 ===");
        com.zero.ai.agentstudy.day17codeagent.patch.PatchResult r = PatchApplier.strict().applyTo(vfs, TextPatch.unique(
                SampleRepository.MONEY,
                "    public static Money zero() {\n        return new Money(BigDecimal.ZERO);\n    }",
                "    public static Money zero() {\n        return new Money(BigDecimal.ONE);\n    }"));
        System.out.println("  " + r);

        Path classes2 = Files.createTempDirectory("day17-test-classes2");
        CompileVerifier.CompilationResult recompiled = verifier.compile(vfs, classes2);
        System.out.println("  重新编译 → " + recompiled.summary()
                + "（注意：编译照样通过！语法完全合法）");

        System.out.println("\n=== [4] 再次运行测试：断言抓住了这个错误 ===");
        var suite2 = testVerifier.runAll(classes2, List.of("demo.order.MoneyTest"));
        System.out.println("  " + suite2);
        suite2.results().forEach(t -> {
            System.out.println("    " + t);
            t.output().lines().filter(l -> l.contains("PASS:") || l.contains("FAIL:"))
                    .forEach(l -> System.out.println("        " + l.strip()));
            System.out.println("    失败信息：");
            t.failures().forEach(f -> System.out.println("        " + f));
        });

        System.out.println("\n=== [5] 小结 ===");
        System.out.println("  编译验证拦不住逻辑错误，必须由测试来拦；");
        System.out.println("  所以 Code Agent 的验证闭环是：编译 → 测试 → 评审，三道缺一不可。");
    }

    /**
     * 一个零依赖的测试类：用 assert + main，靠 -ea 启用断言。
     *
     * <p>约定：每个检查点打印 {@code PASS: xxx} 或 {@code FAIL: xxx}，
     * 便于 {@link TestVerifier} 统计断言数量。</p>
     */
    private static String testSource() {
        return """
                package demo.order;

                import java.math.BigDecimal;

                public class MoneyTest {

                    public static void main(String[] args) {
                        testZero();
                        testAdd();
                        testGreaterThan();
                        System.out.println("ALL DONE");
                    }

                    private static void testZero() {
                        Money zero = Money.zero();
                        assert zero.amount().compareTo(BigDecimal.ZERO) == 0 : "zero() 应该等于 0";
                        System.out.println("PASS: zero() 返回 0");
                    }

                    private static void testAdd() {
                        Money sum = Money.of(100).add(Money.of(50));
                        assert sum.amount().compareTo(BigDecimal.valueOf(150)) == 0 : "加法结果错误";
                        System.out.println("PASS: add(100, 50) = 150");
                    }

                    private static void testGreaterThan() {
                        assert Money.of(200).greaterThan(Money.of(100)) : "比较结果错误";
                        System.out.println("PASS: 200 > 100");
                    }
                }
                """;
    }
}

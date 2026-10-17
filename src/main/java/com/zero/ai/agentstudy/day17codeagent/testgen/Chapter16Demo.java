package com.zero.ai.agentstudy.day17codeagent.testgen;

import com.zero.ai.agentstudy.day17codeagent.repo.SampleRepository;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;
import com.zero.ai.agentstudy.day17codeagent.sandbox.ProcessSandbox;
import com.zero.ai.agentstudy.day17codeagent.sandbox.SandboxPolicy;
import com.zero.ai.agentstudy.day17codeagent.verify.CompileVerifier;
import com.zero.ai.agentstudy.day17codeagent.verify.TestVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Chapter 16 演示：自动生成测试，并真的编译、真的运行。
 *
 * @author ZeroAi
 */
public class Chapter16Demo {

    public static void main(String[] args) throws Exception {
        VirtualFileSystem vfs = VirtualFileSystem.temp("testgen-demo");
        vfs.writeAll(SampleRepository.files());
        TestGenerator generator = new TestGenerator();

        // ---- 1. 产出测试计划 ----
        System.out.println("=== [1] 依据 Money 的签名生成测试计划 ===");
        String moneySource = vfs.read(SampleRepository.MONEY).content();
        TestPlan plan = generator.plan("demo.order", "Money", moneySource);
        System.out.println("  " + plan);
        plan.cases().forEach(c -> System.out.printf("    %-28s %-10s %s%n",
                c.name(), c.kind().display(), c.description()));
        System.out.println("  覆盖率摘要 = " + generator.coverageSummary("demo.order", "Money", moneySource));

        // ---- 2. 渲染 ----
        String testSource = generator.render(plan);
        System.out.println("\n=== [2] 渲染成可执行测试源码（节选）===");
        testSource.lines().limit(22).forEach(l -> System.out.println("    " + l));
        System.out.println("    ...（共 " + testSource.lines().count() + " 行）");

        // ---- 3. 写进仓库并真的编译 ----
        String testPath = "src/test/java/demo/order/MoneyTest.java";
        vfs.write(testPath, testSource);
        Path classes = Files.createTempDirectory("day17-testgen-classes");
        CompileVerifier verifier = CompileVerifier.defaultVerifier();
        CompileVerifier.CompilationResult compiled = verifier.compile(vfs, classes);
        System.out.println("\n=== [3] 编译（含生成的测试）===");
        System.out.println("  " + compiled);
        compiled.errors().forEach(d -> System.out.println("    " + d));
        if (!compiled.success()) {
            System.out.println("  编译失败：生成的测试若不合法，应当回到自修复循环，而不是直接提交");
            return;
        }

        // ---- 4. 真的跑起来 ----
        System.out.println("\n=== [4] 在沙箱里运行生成的测试 ===");
        Path sandboxRoot = Files.createTempDirectory("day17-testgen-sandbox");
        TestVerifier testVerifier =
                new TestVerifier(new ProcessSandbox(SandboxPolicy.runtimeOnly(), sandboxRoot));
        var suite = testVerifier.runAll(classes, List.of("demo.order.MoneyTest"));
        System.out.println("  " + suite);
        suite.results().forEach(r -> {
            System.out.println("    " + r);
            r.output().lines().filter(l -> l.contains("PASS:") || l.contains("ALL DONE"))
                    .forEach(l -> System.out.println("        " + l.strip()));
        });

        // ---- 5. 这批测试为什么「通得过但不是真保障」----
        System.out.println("\n=== [5] 诚实交代：占位断言的价值与边界 ===");
        System.out.println("  这批断言写的是 assert true，所以永远会通过。");
        System.out.println("  它的真正价值在于：『编译 + 运行 + 红绿循环』这条管道已经打通，");
        System.out.println("  只要把第 2 步的渲染替换成 LLM 生成的真实断言，这条管道就直接可用。");
        System.out.println("  生成的测试失败时会自动进自修复循环，人只需要最终审阅。");
        System.out.println("  若不做这一步，『让模型顺便写测试』往往演变成一堆编译不过的测试代码。");

        // ---- 6. 对另一个类也生成一份 ----
        System.out.println("\n=== [6] 对 DiscountPolicy 再生成一份 ===");
        String policySource = vfs.read(SampleRepository.DISCOUNT_POLICY).content();
        TestPlan plan2 = generator.plan("demo.order", "DiscountPolicy", policySource);
        System.out.println("  " + plan2);
    }
}

package com.zero.ai.agentstudy.day17codeagent.verify;

import com.zero.ai.agentstudy.day17codeagent.sandbox.ProcessSandbox;
import com.zero.ai.agentstudy.day17codeagent.tools.CommandRequest;
import com.zero.ai.agentstudy.day17codeagent.tools.ExecResult;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试验证器：真正运行测试并判定通过与否。
 *
 * <p>编译通过 ≠ 正确。<b>「能编译」只保证语法合法，不保证行为正确。</b>
 * 所以编译之后必须再跑测试。</p>
 *
 * <p>本实现刻意<b>不依赖 JUnit 运行时</b>：用 {@code assert} 写一个带 main 的测试类，
 * 通过沙箱以 {@code -ea} 执行，用退出码与 stderr 里的 {@code AssertionError} 判定结果。
 * 这样 Chapter 16（测试生成）产出的测试可以立刻被执行，而不必等到引入测试框架。</p>
 *
 * <p><b>诚实交代局限</b>：真实项目应当换 JUnit 5 + Surefire，
 * 这里是为了「零依赖可跑通」刻意简化。</p>
 *
 * @author ZeroAi
 */
public class TestVerifier {

    private final ProcessSandbox sandbox;

    public TestVerifier(ProcessSandbox sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * 运行一个测试类（含 main 方法）。
     *
     * @param classesDir   class 文件所在目录
     * @param testClassName 测试类的全限定名，如 {@code demo.order.MoneyTest}
     */
    public TestExecutionResult runTest(Path classesDir, String testClassName) {
        // 必须显式带上编码参数：沙箱清空了宿主环境变量，子进程会回退到平台默认编码
        //（中文 Windows 上是 GBK），中文日志与断言消息会变成乱码，排查问题时非常痛苦。
        ExecResult result = sandbox.exec(new CommandRequest(
                "java",
                List.of("-ea", "-Dfile.encoding=UTF-8",
                        "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                        "-cp", classesDir.toString(), testClassName),
                null, Duration.ofSeconds(60), 64 * 1024));

        String output = result.combinedOutput();
        List<String> failures = parseFailures(output);

        if (result.timedOut()) {
            return new TestExecutionResult(testClassName, false, 0, 0,
                    output, List.of("测试超时（可能存在死循环）"), result.elapsedMs());
        }
        boolean passed = result.ok() && failures.isEmpty();
        int total = countAssertions(output);
        int passedCount = passed ? total : Math.max(0, total - failures.size());
        return new TestExecutionResult(testClassName, passed, passedCount, total,
                output, failures, result.elapsedMs());
    }

    /**
     * 批量运行测试类。
     */
    public TestSuiteResult runAll(Path classesDir, List<String> testClassNames) {
        List<TestExecutionResult> results = new ArrayList<>();
        for (String name : testClassNames) {
            results.add(runTest(classesDir, name));
        }
        return new TestSuiteResult(List.copyOf(results));
    }

    private static List<String> parseFailures(String output) {
        List<String> failures = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return failures;
        }
        for (String line : output.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.contains("AssertionError")
                    || trimmed.startsWith("AssertionFailed")
                    || trimmed.contains("Exception in thread")) {
                failures.add(trimmed);
            }
        }
        return List.copyOf(failures);
    }

    /** 统计输出里报告的断言数量（约定测试类每行打印 "PASS: xxx" 或 "FAIL: xxx"）。 */
    private static int countAssertions(String output) {
        if (output == null) {
            return 0;
        }
        return (int) output.lines()
                .filter(l -> l.contains("PASS:") || l.contains("FAIL:"))
                .count();
    }

    /**
     * 单个测试类的执行结果。
     *
     * @param testClass 测试类名
     * @param passed    是否通过
     * @param passedCount 通过断言数
     * @param totalCount  总断言数
     * @param output    原始输出
     * @param failures  失败信息
     * @param elapsedMs 耗时
     */
    public record TestExecutionResult(
            String testClass,
            boolean passed,
            int passedCount,
            int totalCount,
            String output,
            List<String> failures,
            long elapsedMs
    ) {
        @Override
        public String toString() {
            return String.format("%s %s (%d/%d 断言, %dms)",
                    testClass, passed ? "PASS" : "FAIL", passedCount, totalCount, elapsedMs);
        }
    }

    /**
     * 测试套件结果。
     *
     * @param results 各测试类结果
     */
    public record TestSuiteResult(List<TestExecutionResult> results) {
        public boolean allPassed() {
            return results.stream().allMatch(TestExecutionResult::passed);
        }

        public long passedCount() {
            return results.stream().filter(TestExecutionResult::passed).count();
        }

        public long failedCount() {
            return results.size() - passedCount();
        }

        /** 通过率 [0.0, 1.0]，无测试时返回 0。 */
        public double passRate() {
            if (results.isEmpty()) {
                return 0.0;
            }
            return (double) passedCount() / results.size();
        }

        @Override
        public String toString() {
            return String.format("TestSuiteResult{通过 %d / %d, 通过率 %.1f%%, 耗时 %dms}",
                    passedCount(), results.size(), passRate() * 100,
                    results.stream().mapToLong(TestExecutionResult::elapsedMs).sum());
        }
    }
}

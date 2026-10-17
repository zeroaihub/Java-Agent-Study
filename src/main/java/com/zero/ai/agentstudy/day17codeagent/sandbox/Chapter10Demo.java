package com.zero.ai.agentstudy.day17codeagent.sandbox;

import com.zero.ai.agentstudy.day17codeagent.tools.CommandRequest;
import com.zero.ai.agentstudy.day17codeagent.tools.ExecResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Chapter 10 演示：代码沙箱。
 *
 * <p>运行可见四条防线全部生效：</p>
 * <ol>
 *     <li>可执行文件白名单（默认拒绝）；</li>
 *     <li>参数黑名单（即使可执行文件合法）；</li>
 *     <li>工作目录越界 → 拒绝；</li>
 *     <li>超时 → 强杀，并且<b>能够区分「超时」与「编译报错」</b>。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class Chapter10Demo {

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("day17-sandbox");
        SandboxPolicy policy = SandboxPolicy.defaults();
        ProcessSandbox sandbox = new ProcessSandbox(policy, root);

        System.out.println("=== [1] 沙箱配置 ===");
        System.out.println("  " + policy);
        System.out.println("  沙箱根 = " + sandbox.sandboxRoot());

        // ---- 2. 正常执行 ----
        System.out.println("\n=== [2] 白名单内：真实执行 java -version ===");
        ExecResult version = sandbox.exec(CommandRequest.of("java", "-version"));
        System.out.println("  " + version);
        version.combinedOutput().lines().limit(3).forEach(l -> System.out.println("      " + l));

        // ---- 3. 可执行文件不在名单 ----
        System.out.println("\n=== [3] 白名单外可执行文件 ===");
        for (String bad : List.of("cmd", "rm", "python", "sh")) {
            ExecResult r = sandbox.exec(CommandRequest.of(bad, "--version"));
            System.out.printf("  %-8s → %s%n", bad, r.message());
        }

        // ---- 4. 参数黑名单 ----
        System.out.println("\n=== [4] 参数黑名单（可执行文件合法也没用）===");
        ExecResult danger = sandbox.exec(CommandRequest.of("java", "-version;", "rm", "-rf", "/"));
        System.out.println("  " + danger.message());

        // ---- 5. 工作目录越界 ----
        System.out.println("\n=== [5] 工作目录越界 ===");
        Path outside = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        ExecResult escaped = sandbox.exec(
                CommandRequest.of("javac", "-version").withWorkingDir(outside));
        System.out.println("  " + escaped.message());

        // ---- 6. 超时 strong kill ----
        System.out.println("\n=== [6] 超时强杀：这里给 javac 一个几乎不可能完成的超时窗口 ===");
        // 先写一个真实的 Java 文件，让 javac 有活要干
        Path inside = Files.createDirectories(root.resolve("src"));
        Files.writeString(inside.resolve("Big.java"), """
                public class Big {
                    public static void main(String[] a) {
                        System.out.println("hello sandbox");
                    }
                }
                """);
        ExecResult timedOut = sandbox.exec(new CommandRequest(
                "javac", List.of("Big.java"), inside, java.time.Duration.ofMillis(60), 64 * 1024));
        System.out.println("  " + timedOut);
        System.out.println("  timedOut = " + timedOut.timedOut()
                + " | ok = " + timedOut.ok()
                + " | exitCode = " + timedOut.exitCode());
        System.out.println("  → 关键：timedOut 与 exitCode 是两个独立维度。"
                + "只判 exitCode != 0 会把「超时」误当成「编译报错」，"
                + "自修复循环就会对着根本不存在的语法错误反复重试");

        // ---- 7. 正常编译一次（证明超时不是因为命令本身有问题）----
        System.out.println("\n=== [7] 给足时间：同一次编译可以成功 ===");
        ExecResult compiled = sandbox.exec(new CommandRequest(
                "javac", List.of("Big.java"), inside, java.time.Duration.ofSeconds(30), 64 * 1024));
        System.out.println("  " + compiled);
        System.out.println("  产物是否存在 = " + Files.exists(inside.resolve("Big.class")));

        // ---- 8. 环境变量隔离 ----
        System.out.println("\n=== [8] 为什么必须 clears 宿主环境变量 ===");
        System.out.println("  宿主环境变量数量 ≈ " + System.getenv().size());
        System.out.println("  沙箱内继承？ policy.inheritEnv = " + policy.inheritEnv());
        System.out.println("  → 一旦继承，Agent 生成的代码可以 System.getenv(\"OPENAI_API_KEY\") 把密钥打出来");

        // ---- 9. 为什么必须开线程读输出 ----
        System.out.println("\n=== [9] 为什么(subprocess)输出必须异步读 ===");
        System.out.println("  子进程 stdout 缓冲区只有几 KB：");
        System.out.println("  如果主线程先 waitFor 再读，缓冲区写满后子进程会阻塞，");
        System.out.println("  于是「一个本来 100ms 的程序」永远等不到结束 —— 表现为莫名其妙全部超时。");
    }
}

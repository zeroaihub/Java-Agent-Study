package com.zero.ai.agentstudy.day17codeagent.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 本地命令执行器（Chapter 04 版本）：白名单 + 无 shell + 超时。
 *
 * <p>它是 {@link CommandExecutor} 的一个「够用但克制」的实现，
 * Chapter 10 的 {@code ProcessSandbox} 会在此之上补齐资源限制与工作目录隔离。</p>
 *
 * <h3>三条约束</h3>
 * <ol>
 *     <li><b>白名单先行</b>：不在名单里的可执行文件直接拒绝，<b>默认拒绝</b>而不是默认放行；</li>
 *     <li><b>无 shell</b>：{@code ProcessBuilder} 直接 exec，不经过 {@code cmd /c} 或 {@code sh -c}，
 *         因此 {@code ; rm -rf}、{@code |}、反引号这些都失去了作用；</li>
 *     <li><b>必须超时</b>：LLM 让它跑个死循环是真的会跑到底的。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class LocalCommandExecutor implements CommandExecutor {

    /** 允许的可执行文件名（小写）。刻意极小：够跑 javac / java 即可。 */
    private final Set<String> allowlist;

    public LocalCommandExecutor(Set<String> allowlist) {
        this.allowlist = Set.copyOf(allowlist);
    }

    /** 默认名单：只允许 java 与 javac。 */
    public static LocalCommandExecutor javaOnly() {
        return new LocalCommandExecutor(Set.of("java", "javac"));
    }

    public Set<String> allowlist() {
        return allowlist;
    }

    @Override
    public ExecResult exec(CommandRequest request) {
        if (request == null) {
            return ExecResult.denied("请求为空");
        }
        String executable = request.executable();
        if (executable == null || executable.isBlank()) {
            return ExecResult.denied("可执行文件名为空");
        }
        String lower = executable.toLowerCase();
        if (!allowlist.contains(lower)) {
            return ExecResult.denied("可执行文件不在白名单内：" + executable + "，允许：" + allowlist);
        }

        long start = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder();
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add(resolveJavaBin(lower));
            command.addAll(request.args());
            pb.command(command);

            if (request.workingDir() != null) {
                pb.directory(request.workingDir().toFile());
            }
            // 干净环境：不继承本机可能存在的敏感环境变量
            pb.environment().clear();
            pb.environment().put("JAVA_HOME", Path.of(System.getProperty("java.home")).toString());
            pb.environment().put("PATH", Path.of(System.getProperty("java.home"), "bin").toString());

            Process process = pb.start();
            String stdout = readFully(process.getInputStream());
            String stderr = readFully(process.getErrorStream());

            boolean finished = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;

            if (!finished) {
                process.destroyForcibly();
                return ExecResult.timeout(elapsed, stdout);
            }
            int exit = process.exitValue();
            if (exit != 0) {
                return ExecResult.failure(exit, stdout, stderr, elapsed);
            }
            return ExecResult.success(stdout.isEmpty() ? stderr : stdout, elapsed);
        } catch (IOException e) {
            return ExecResult.failure(-1, "", "无法启动进程：" + e.getMessage(),
                    System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecResult.failure(-2, "", "执行被中断", System.currentTimeMillis() - start);
        }
    }

    /** 把白名单里的名字解析为当前 JDK 的真实可执行文件路径（避免依赖 PATH）。 */
    private String resolveJavaBin(String lower) {
        Path bin = Path.of(System.getProperty("java.home"), "bin",
                lower + (System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : ""));
        if (java.nio.file.Files.exists(bin)) {
            return bin.toString();
        }
        return lower;
    }

    private String readFully(InputStream in) throws IOException {
        try (in) {
            byte[] bytes = in.readAllBytes();
            String s = new String(bytes, StandardCharsets.UTF_8);
            return s.length() > 8192 ? s.substring(0, 8192) + "\n...(已截断)" : s;
        }
    }
}

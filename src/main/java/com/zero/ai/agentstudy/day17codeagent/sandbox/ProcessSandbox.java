package com.zero.ai.agentstudy.day17codeagent.sandbox;

import com.zero.ai.agentstudy.day17codeagent.tools.CommandExecutor;
import com.zero.ai.agentstudy.day17codeagent.tools.CommandRequest;
import com.zero.ai.agentstudy.day17codeagent.tools.ExecResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 进程沙箱：{@link CommandExecutor} 的生产化实现。
 *
 * <p>与 Chapter 04 的 {@code LocalCommandExecutor} 相比，它补齐了：</p>
 * <ol>
 *     <li><b>策略化</b>：超时、输出上限、允许的可执行文件、禁用参数片段全部可配；</li>
 *     <li><b>工作目录隔离</b>：必须落在 {@code sandboxRoot} 之下，一层一层剥 {@code ..} 也没用；</li>
 *     <li><b>环境变量隔离</b>：默认不继承宿主环境，杜绝「模型读走 {@code OPENAI_API_KEY}」；</li>
 *     <li><b>输出上限</b>：超过就不再读，防止把 Agent 自己的内存打爆。</li>
 * </ol>
 *
 * <p>同一份 {@code RunCommandTool} 代码，换个执行器就从「本地白名单」升级成「真沙箱」——
 * 这就是端口 + 适配器带来的收益。</p>
 *
 * <p><b>诚实交代</b>：这仍然是「进程级」隔离，不是容器级。真正生产应当跑在
 * gVisor / Docker / Kata Containers 里，并限制网络与文件系统挂载。
 * 但对「LLM 生成的一段学生作业性质的 Java 代码」而言，这一层已经挡住了绝大多数真实风险。</p>
 *
 * @author ZeroAi
 */
public class ProcessSandbox implements CommandExecutor {

    private final SandboxPolicy policy;
    private final Path sandboxRoot;

    public ProcessSandbox(SandboxPolicy policy, Path sandboxRoot) {
        this.policy = policy;
        this.sandboxRoot = sandboxRoot.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.sandboxRoot);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建沙箱根目录：" + sandboxRoot, e);
        }
    }

    public SandboxPolicy policy() {
        return policy;
    }

    public Path sandboxRoot() {
        return sandboxRoot;
    }

    @Override
    public ExecResult exec(CommandRequest request) {
        if (request == null) {
            return ExecResult.denied("请求为空");
        }
        String executable = request.executable();
        List<String> args = request.args() == null ? List.of() : request.args();

        // ---- 闸门 1：策略（可执行文件 + 参数黑名单） ----
        if (policy.denies(executable, args)) {
            return ExecResult.denied(
                    "可执行文件不在允许列表内或参数命中黑名单：" + executable + " " + args);
        }
        // ---- 闸门 2：工作目录必须落在沙箱根内 ----
        if (request.workingDir() != null && !policy.permitsWorkingDir(request.workingDir(), sandboxRoot)) {
            return ExecResult.denied("工作目录越界：" + request.workingDir()
                    + "，必须位于沙箱根 " + sandboxRoot + " 之内");
        }

        long start = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder();
            List<String> command = new ArrayList<>();
            command.add(resolveBinary(executable));
            command.addAll(args);
            pb.command(command);

            Path workingDir = request.workingDir() == null ? sandboxRoot : request.workingDir();
            pb.directory(workingDir.toFile());

            // ---- 闸门 3：环境变量隔离 ----
            pb.environment().clear();
            if (policy.inheritEnv()) {
                pb.environment().putAll(System.getenv());
            }
            // 无论是否继承，都不允许把宿主 PATH 带进去（否则可以间接执行任意命令）
            pb.environment().put("PATH", Path.of(System.getProperty("java.home"), "bin").toString());
            pb.environment().put("JAVA_HOME", System.getProperty("java.home"));

            Process process = pb.start();
            StreamGobbler stdout = new StreamGobbler(process.getInputStream(), policy.maxOutputBytes());
            StreamGobbler stderr = new StreamGobbler(process.getErrorStream(), policy.maxOutputBytes());
            Thread t1 = new Thread(stdout);
            Thread t2 = new Thread(stderr);
            t1.start();
            t2.start();

            long effectiveTimeout = request.timeout().toMillis() > 0
                    ? request.timeout().toMillis() : policy.timeoutMs();
            boolean finished = process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;

            if (!finished) {
                // 先中断读线程，再强杀进程：顺序反了会读到半截数据
                t1.interrupt();
                t2.interrupt();
                process.destroyForcibly();
                return ExecResult.timeout(elapsed, stdout.snapshot());
            }
            t1.join(1000);
            t2.join(1000);

            int exit = process.exitValue();
            String out = stdout.snapshot();
            String err = stderr.snapshot();
            if (exit != 0) {
                return ExecResult.failure(exit, out, err, elapsed);
            }
            return ExecResult.success(out.isEmpty() ? err : out, elapsed);
        } catch (IOException e) {
            return ExecResult.failure(-1, "", "无法启动进程：" + e.getMessage(),
                    System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecResult.failure(-2, "", "等待进程时被中断",
                    System.currentTimeMillis() - start);
        }
    }

    /** 解析可执行文件路径：优先用当前 JDK 自带的 java / javac，不依赖宿主 PATH。 */
    private String resolveBinary(String executable) {
        Path bin = Path.of(System.getProperty("java.home"), "bin",
                executable + (System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : ""));
        if (Files.exists(bin)) {
            return bin.toString();
        }
        return executable;
    }

    /**
     * 输出读取器：独立线程 + 上限截断。
     *
     * <p>为什么必须开线程读：子进程输出缓冲区一旦写满，它会<b>阻塞不动</b>等待读取，
     * 于是 waitFor 永远超时——这是「明明很快的程序却总是超时」的头号原因。</p>
     */
    private static class StreamGobbler implements Runnable {
        private final InputStream in;
        private final int limit;
        private final StringBuilder buffer = new StringBuilder();
        private volatile boolean truncated;

        StreamGobbler(InputStream in, int limit) {
            this.in = in;
            this.limit = limit;
        }

        @Override
        public void run() {
            try (in) {
                byte[] chunk = new byte[4096];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    if (buffer.length() < limit) {
                        buffer.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                    } else {
                        truncated = true;
                    }
                }
            } catch (IOException ignored) {
                // 进程被强杀时读取会抛异常，属于预期行为
            }
        }

        String snapshot() {
            String s = buffer.length() > limit ? buffer.substring(0, limit) : buffer.toString();
            return truncated ? s + "\n...(输出已截断)" : s;
        }
    }
}

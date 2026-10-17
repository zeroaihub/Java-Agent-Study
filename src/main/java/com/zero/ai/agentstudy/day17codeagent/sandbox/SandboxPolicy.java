package com.zero.ai.agentstudy.day17codeagent.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * 沙箱策略（不可变）。
 *
 * <h3>为什么必须有沙箱</h3>
 * <p>让 LLM 生成的代码在你本机跑，等价于给一个陌生人 root 权限。
 * 真实事故清单：</p>
 * <ul>
 *     <li>死循环 → 占满 CPU，整台机器不可用；</li>
 *     <li>无限打印 / 无限分配内存 → OOM；</li>
 *     <li>{@code Files.delete(Path.of("/"))} → 数据全丢；</li>
 *     <li>读取环境变量里的密钥 → 凭证泄露。</li>
 * </ul>
 *
 * <p>沙箱策略把上述风险逐条变成<b>可检查的字段</b>，而不是「希望模型别乱来」。</p>
 *
 * @param timeoutMs        超时毫秒，超时即强杀
 * @param maxOutputBytes   输出字节上限，超出截断
 * @param allowedExecutables 允许的可执行文件名（默认拒绝）
 * @param forbiddenArgs    参数里命中这些片段就拒绝（多一道保险，防 laat-minute 注入）
 * @param inheritEnv       是否继承宿主环境变量（默认 <b>false</b>，防密钥泄露）
 * @param maxAttempts      同一任务允许的最大执行次数
 * @author ZeroAi
 */
public record SandboxPolicy(
        long timeoutMs,
        int maxOutputBytes,
        Set<String> allowedExecutables,
        Set<String> forbiddenArgs,
        boolean inheritEnv,
        int maxAttempts
) {

    /** 教学默认：只允许 java / javac，30 秒超时，不继承环境变量。 */
    public static SandboxPolicy defaults() {
        return new SandboxPolicy(
                30_000L,
                256 * 1024,
                Set.of("java", "javac"),
                Set.of("rm -rf", "sudo", "shutdown", "format", "del /f", "mkfs", ":(){", ">/dev/sd"),
                false,
                3);
    }

    /** 更严格的 CI 场景：连 javac 都不给，只能跑已经编译好的程序。 */
    public static SandboxPolicy runtimeOnly() {
        return new SandboxPolicy(10_000L, 64 * 1024, Set.of("java"),
                Set.of("rm -rf", "sudo", "format", "mkfs"), false, 2);
    }

    public SandboxPolicy {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("沙箱必须有超时：没有超时的执行等于给 Agent 一个死循环开关");
        }
        allowedExecutables = allowedExecutables == null ? Set.of() : Set.copyOf(allowedExecutables);
        forbiddenArgs = forbiddenArgs == null ? Set.of() : Set.copyOf(forbiddenArgs);
    }

    /** 命令是否被禁止（先看可执行文件，再看参数片段）。 */
    public boolean denies(String executable, java.util.List<String> args) {
        if (!allowedExecutables.contains(executable.toLowerCase())) {
            return true;
        }
        String joined = String.join(" ", args).toLowerCase();
        return forbiddenArgs.stream().anyMatch(bad -> joined.contains(bad.toLowerCase()));
    }

    public SandboxPolicy withTimeout(long millis) {
        return new SandboxPolicy(millis, maxOutputBytes, allowedExecutables, forbiddenArgs,
                inheritEnv, maxAttempts);
    }

    /** 检查某个工作目录是否在允许的根之下。 */
    public boolean permitsWorkingDir(Path dir, Path allowedRoot) {
        if (dir == null) {
            return true;
        }
        Path normalized = dir.toAbsolutePath().normalize();
        return normalized.startsWith(allowedRoot.toAbsolutePath().normalize());
    }

    public Duration timeout() {
        return Duration.ofMillis(timeoutMs);
    }

    @Override
    public String toString() {
        return "SandboxPolicy{timeout=" + timeoutMs + "ms, maxOutput=" + maxOutputBytes
                + "B, allow=" + allowedExecutables + ", inheritEnv=" + inheritEnv + "}";
    }
}

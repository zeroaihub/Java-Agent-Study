package com.zero.ai.agentstudy.day17codeagent.tools;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 命令执行请求（不可变）。
 *
 * <p>刻意<b>不收整条命令行字符串</b>——shell 字符串会带来注入面（{@code ; rm -rf /}、
 * 反引号、管道重定向）。这里收「可执行文件名 + 参数列表」，由执行器以无 shell 的方式
 * {@code ProcessBuilder} 直接 exec，从根上砍掉 shell 元字符攻击。</p>
 *
 * @param executable 可执行文件名（不含路径时由执行器按白名单解析）
 * @param args       参数列表
 * @param workingDir 工作目录，必须落在沙箱之内
 * @param timeout    超时
 * @param maxOutputBytes 输出上限，防止「程序疯狂打印把 Agent 内存打爆」
 * @author ZeroAi
 */
public record CommandRequest(
        String executable,
        List<String> args,
        Path workingDir,
        Duration timeout,
        int maxOutputBytes
) {

    public static CommandRequest of(String executable, String... args) {
        return new CommandRequest(executable, List.of(args), null, Duration.ofSeconds(10), 64 * 1024);
    }

    public CommandRequest withWorkingDir(Path dir) {
        return new CommandRequest(executable, args, dir, timeout, maxOutputBytes);
    }

    public CommandRequest withTimeout(Duration t) {
        return new CommandRequest(executable, args, workingDir, t, maxOutputBytes);
    }

    @Override
    public String toString() {
        return executable + " " + String.join(" ", args);
    }
}

package com.zero.ai.agentstudy.day17codeagent.tools;

/**
 * 命令执行端口。
 *
 * <p>把「怎么执行命令」从工具里抽出来，是为了让同一个 {@code RunCommandTool}
 * 既能接 Chapter 04 的本地白名单执行器，也能在 Chapter 10 无缝换成真正的
 * {@code ProcessSandbox}（进程 + 资源限制 + 超时 + 工作目录隔离）。
 * 换实现不用动工具代码，这正是端口的意义。</p>
 *
 * @author ZeroAi
 */
public interface CommandExecutor {

    ExecResult exec(CommandRequest request);
}

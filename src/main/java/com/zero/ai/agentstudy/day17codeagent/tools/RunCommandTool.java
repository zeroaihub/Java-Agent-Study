package com.zero.ai.agentstudy.day17codeagent.tools;

import com.zero.ai.agentstudy.day17codeagent.core.model.CodeTaskContext;

import java.util.List;
import java.util.Map;

/**
 * run_command：执行命令（编译、跑测试、跑脚本）。
 *
 * <p>工具本身<b>不包含任何执行逻辑</b>，全部委派给 {@link CommandExecutor} 端口。
 * 这样同一个工具在 Chapter 04 用白名单本地执行器，在 Chapter 10 直接换成沙箱。</p>
 *
 * <p>工具的职责只剩三件：拼参数、判权限、把结果翻译成模型看得懂的话。</p>
 *
 * @author ZeroAi
 */
public class RunCommandTool implements Tool {

    private final CommandExecutor executor;

    public RunCommandTool(CommandExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() {
        return "run_command";
    }

    @Override
    public String description() {
        return "执行一条命令（java / javac），用于编译与运行验证。";
    }

    @Override
    public List<String> parameters() {
        return List.of("executable(必填)：java 或 javac", "args(可选)：空格分隔的参数");
    }

    @Override
    public ToolResult invoke(CodeTaskContext context, Map<String, String> args) {
        long start = System.currentTimeMillis();
        if (context == null) {
            return ToolResult.failure(name(), "缺少任务上下文", elapsed(start));
        }
        // 危险权限：必须显式拥有 CODE_EXECUTOR
        if (!context.canExecute()) {
            return ToolResult.failure(name(),
                    "当前用户 " + context.userId() + " 没有命令执行权限（需要 CODE_EXECUTOR 或 ADMIN 角色）。",
                    elapsed(start));
        }
        Map<String, String> safeArgs = args == null ? Map.of() : args;
        String executable = safeArgs.get("executable");
        if (executable == null || executable.isBlank()) {
            return ToolResult.failure(name(), "缺少参数 executable", elapsed(start));
        }
        String rawArgs = safeArgs.getOrDefault("args", "");
        List<String> argList = rawArgs.isBlank()
                ? List.of()
                : List.of(rawArgs.trim().split("\\s+"));

        CommandRequest request = new CommandRequest(
                executable, argList, null,
                java.time.Duration.ofSeconds(20), 64 * 1024);

        ExecResult result = executor.exec(request);
        if (!result.ok()) {
            String detail = result.timedOut()
                    ? "命令执行超时，已被强制终止。建议：改用更小的输入，或修正可能的死循环。"
                    : "命令失败：" + result.combinedOutput();
            return ToolResult.failure(name(), detail, System.currentTimeMillis() - start);
        }
        return ToolResult.success(name(), result.combinedOutput(), System.currentTimeMillis() - start);
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}

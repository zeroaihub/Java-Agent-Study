package com.zero.ai.agentstudy.day17codeagent.tools;

import com.zero.ai.agentstudy.day17codeagent.core.model.CodeTaskContext;
import com.zero.ai.agentstudy.day17codeagent.core.model.RepoId;
import com.zero.ai.agentstudy.day17codeagent.core.model.TaskId;
import com.zero.ai.agentstudy.day17codeagent.core.model.TenantId;
import com.zero.ai.agentstudy.day17codeagent.core.model.UserId;
import com.zero.ai.agentstudy.day17codeagent.repo.SampleRepository;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;

import java.util.Map;
import java.util.Set;

/**
 * Chapter 04 演示：Agent 工具集与注册表。
 *
 * <p>运行可见：</p>
 * <ol>
 *     <li>工具清单会动态拼成提示词片段；</li>
 *     <li>五种工具各跑一次；</li>
 *     <li><b>工具失败是返回值而不是异常</b>：文件不存在、正则写错都不抛异常，
 *         而是给出「可改正」的错误文案；</li>
 *     <li><b>默认拒绝</b>：没有写 / 执行权限会被挡住；</li>
 *     <li><b>只读模式不注册写工具</b>：模型根本看不到它，比事后拒绝更安全。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class Chapter04Demo {

    public static void main(String[] args) {
        VirtualFileSystem vfs = VirtualFileSystem.temp("tools-demo");
        vfs.writeAll(SampleRepository.files());

        CommandExecutor executor = LocalCommandExecutor.javaOnly();
        ToolRegistry registry = ToolRegistry.full(vfs, executor);

        // ---- 1. 工具清单 → 提示词片段 ----
        System.out.println("=== [1] 工具清单（这就是要拼进提示词的内容）===");
        System.out.println(registry.describe());

        // ---- 2. 全能上下文 ----
        CodeTaskContext admin = CodeTaskContext.of(
                TenantId.of("tenant-acme"), RepoId.of("order-service"), UserId.of("zhangsan"),
                Set.of("CODE_WRITER", "CODE_EXECUTOR", "ADMIN"), TaskId.of("task-101"));

        // ---- 3. 逐个调用 ----
        System.out.println("=== [2] list_files ===");
        printResult(registry, admin, "list_files", Map.of("suffix", ".java"));

        System.out.println("\n=== [3] read_file（带行区间）===");
        printResult(registry, admin, "read_file",
                Map.of("path", SampleRepository.MONEY, "from", "6", "to", "12"));

        System.out.println("\n=== [4] search_code ===");
        printResult(registry, admin, "search_code",
                Map.of("pattern", "discount", "suffix", ".java"));

        System.out.println("\n=== [5] write_file（有写权限）===");
        printResult(registry, admin, "write_file",
                Map.of("path", "src/main/java/demo/order/Notes.java",
                        "content", "package demo.order;\n\npublic class Notes {\n}\n"));

        System.out.println("\n=== [6] run_command（真实执行 java -version）===");
        printResult(registry, admin, "run_command", Map.of("executable", "java", "args", "-version"));

        // ---- 4. 失败 = 返回值 ----
        System.out.println("\n=== [7] 失败应当是返回值，不是异常 ===");
        printResult(registry, admin, "read_file", Map.of("path", "no/such/File.java"));
        printResult(registry, admin, "search_code", Map.of("pattern", "[unclosed"));
        printResult(registry, admin, "read_file",
                Map.of("path", SampleRepository.MONEY, "from", "30", "to", "5"));

        // ---- 5. 权限：默认拒绝 ----
        System.out.println("\n=== [8] 权限默认拒绝 ===");
        CodeTaskContext intern = CodeTaskContext.of(
                TenantId.of("tenant-acme"), RepoId.of("order-service"), UserId.of("intern-01"),
                Set.of("VIEWER"), TaskId.of("task-102"));
        System.out.println("  上下文 = " + intern.userId() + " roles=" + intern.roles()
                + " canWrite=" + intern.canWrite() + " canExecute=" + intern.canExecute());
        printResult(registry, intern, "write_file",
                Map.of("path", "hack.java", "content", "// x"));
        printResult(registry, intern, "run_command", Map.of("executable", "java", "args", "-version"));

        // ---- 6. 只读注册表：不注册写工具 ----
        System.out.println("\n=== [9] 只读模式：干脆让模型看不到写工具 ===");
        ToolRegistry readOnly = ToolRegistry.readOnly(vfs);
        System.out.println("  只读工具 = " + readOnly.names());
        System.out.println("  写工具可见？ write_file = " + readOnly.contains("write_file")
                + " | run_command = " + readOnly.contains("run_command"));
        Tool missing = readOnly.find("write_file");
        System.out.println("  强行按名字取：find(\"write_file\") = " + missing
                + "（返回 null，由调用方决定降级，不抛异常）");
        System.out.println("  白名单执行器允许的可执行文件 = " + ((LocalCommandExecutor) executor).allowlist());
        // 白名单之外的命令
        ExecResult denied = executor.exec(CommandRequest.of("cmd", "/c", "echo hi"));
        System.out.println("  白名单外命令 → " + denied.message());
    }

    private static void printResult(ToolRegistry registry, CodeTaskContext ctx,
                                    String toolName, Map<String, String> args) {
        Tool tool = registry.find(toolName);
        if (tool == null) {
            System.out.println("  " + toolName + " → 工具未注册");
            return;
        }
        ToolResult result = tool.invoke(ctx, args);
        System.out.println("  " + toolName + " → " + (result.success() ? "OK" : "FAIL")
                + " (" + result.elapsedMs() + "ms)");
        String body = result.success() ? result.output() : result.error();
        indent(body);
    }

    private static void indent(String text) {
        if (text == null) {
            return;
        }
        text.lines().limit(8).forEach(line -> System.out.println("      | " + line));
    }
}

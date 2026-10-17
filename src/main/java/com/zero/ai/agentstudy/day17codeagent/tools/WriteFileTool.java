package com.zero.ai.agentstudy.day17codeagent.tools;

import com.zero.ai.agentstudy.day17codeagent.core.model.CodeTaskContext;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;

import java.util.List;
import java.util.Map;

/**
 * write_file：写入/覆盖文件。
 *
 * <p>这是<b>唯一能改变仓库状态</b>的工具，所以它背负了三条护栏：</p>
 * <ol>
 *     <li><b>角色校验</b>：{@code ctx.canWrite()} 为假时直接拒绝。权限从上下文取，
 *         绝不接受模型在参数里自称管理员；</li>
 *     <li><b>路径校验</b>：交给 VFS，双保险；</li>
 *     <li><b>返回可对照的结果</b>：写完之后回报实际行数与字节数，
 *         让模型能自查「我是不是写少了」。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class WriteFileTool implements Tool {

    private final VirtualFileSystem vfs;

    public WriteFileTool(VirtualFileSystem vfs) {
        this.vfs = vfs;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "把内容写入仓库内的文件（整体覆盖）。需要有写权限。";
    }

    @Override
    public List<String> parameters() {
        return List.of("path(必填)：文件相对路径", "content(必填)：文件完整内容");
    }

    @Override
    public ToolResult invoke(CodeTaskContext context, Map<String, String> args) {
        long start = System.currentTimeMillis();
        if (context == null) {
            return ToolResult.failure(name(), "缺少任务上下文", elapsed(start));
        }
        // 护栏 1：权限（默认拒绝）
        if (!context.canWrite()) {
            return ToolResult.failure(name(),
                    "当前用户 " + context.userId() + " 没有写权限（需要 CODE_WRITER 或 ADMIN 角色）。"
                            + "建议：改为只读分析，或联系管理员申请权限。",
                    elapsed(start));
        }
        String path = args == null ? null : args.get("path");
        String content = args == null ? null : args.get("content");
        if (path == null || path.isBlank()) {
            return ToolResult.failure(name(), "缺少参数 path", elapsed(start));
        }
        if (content == null) {
            return ToolResult.failure(name(), "缺少参数 content", elapsed(start));
        }
        try {
            // 护栏 2：路径由 VFS 兜底
            vfs.write(path, content);
            com.zero.ai.agentstudy.day17codeagent.repo.RepoFile written = vfs.read(path);
            return ToolResult.success(name(),
                    "已写入 " + path + "：" + written.lineCount() + " 行，" + written.sizeBytes() + " 字节。",
                    elapsed(start));
        } catch (RuntimeException e) {
            // 护栏 3：把失败原因完整交回给模型，由它自行修正
            return ToolResult.failure(name(), "写入失败：" + e.getMessage(), elapsed(start));
        }
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}

package com.zero.ai.agentstudy.day17codeagent.tools;

import com.zero.ai.agentstudy.day17codeagent.core.model.CodeTaskContext;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;

import java.util.List;
import java.util.Map;

/**
 * list_files：列出仓库内文件。
 *
 * <p>为什么要单独做一个工具，而不是让模型直接猜路径：LLM 对仓库里「到底有哪些文件」
 * 的先验几乎为零，指望它猜会导致大量 {@code FILE_NOT_FOUND} 空转。
 * 给一个廉价的目录工具，能把试错成本从「一轮对话」降到「一次工具调用」。</p>
 *
 * @author ZeroAi
 */
public class ListFilesTool implements Tool {

    private final VirtualFileSystem vfs;

    public ListFilesTool(VirtualFileSystem vfs) {
        this.vfs = vfs;
    }

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public String description() {
        return "列出仓库内的文件路径，可按后缀过滤。";
    }

    @Override
    public List<String> parameters() {
        return List.of("dir(可选)：子目录，默认仓库根", "suffix(可选)：后缀过滤，如 .java");
    }

    @Override
    public ToolResult invoke(CodeTaskContext context, Map<String, String> args) {
        long start = System.currentTimeMillis();
        Map<String, String> safeArgs = args == null ? Map.of() : args;
        String dir = safeArgs.getOrDefault("dir", "");
        String suffix = safeArgs.get("suffix");

        List<String> files = (suffix == null || suffix.isBlank())
                ? vfs.listFiles(dir)
                : vfs.listFilesEndingWith(dir, suffix);

        if (files.isEmpty()) {
            return ToolResult.success(name(), "（空）该目录下没有匹配文件。", elapsed(start));
        }
        StringBuilder sb = new StringBuilder("共 " + files.size() + " 个文件：\n");
        files.forEach(f -> sb.append("  ").append(f).append('\n'));
        return ToolResult.success(name(), sb.toString().stripTrailing(), elapsed(start));
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}

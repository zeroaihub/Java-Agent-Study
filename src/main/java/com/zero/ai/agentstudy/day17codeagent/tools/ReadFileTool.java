package com.zero.ai.agentstudy.day17codeagent.tools;

import com.zero.ai.agentstudy.day17codeagent.core.model.CodeTaskContext;
import com.zero.ai.agentstudy.day17codeagent.repo.RepoFile;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;

import java.util.List;
import java.util.Map;

/**
 * read_file：读取文件内容（可指定行区间）。
 *
 * <p><b>为什么必须支持行区间</b>：一个 800 行的文件全量塞进上下文，占掉的 token
 * 够读 20 个小文件。真实仓库里一次读全文会瞬间撑爆预算。默认行为应当是「有节制地读」。</p>
 *
 * @author ZeroAi
 */
public class ReadFileTool implements Tool {

    private final VirtualFileSystem vfs;

    public ReadFileTool(VirtualFileSystem vfs) {
        this.vfs = vfs;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取仓库内某个文件的内容，可指定行区间。";
    }

    @Override
    public List<String> parameters() {
        return List.of("path(必填)：文件相对路径", "from(可选)：起始行，1-based", "to(可选)：结束行");
    }

    @Override
    public ToolResult invoke(CodeTaskContext context, Map<String, String> args) {
        long start = System.currentTimeMillis();
        String path = args == null ? null : args.get("path");
        if (path == null || path.isBlank()) {
            return ToolResult.failure(name(), "缺少参数 path", elapsed(start));
        }
        RepoFile file = vfs.find(path);
        if (file == null) {
            // 关键：把「可用的路径」一并告诉模型，它下一轮就能改对
            return ToolResult.failure(name(),
                    "文件不存在：" + path + "。建议先调用 list_files 查看可用路径。",
                    elapsed(start));
        }
        String from = args.get("from");
        String to = args.get("to");
        String content;
        if (from != null || to != null) {
            int f = parseIntOrDefault(from, 1);
            int t = parseIntOrDefault(to, file.lineCount());
            if (f > t) {
                return ToolResult.failure(name(),
                        "参数非法：from=" + f + " 大于 to=" + t, elapsed(start));
            }
            content = file.lines(f, t);
        } else {
            content = file.content();
        }
        return ToolResult.success(name(), content, elapsed(start));
    }

    private static int parseIntOrDefault(String s, int def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}

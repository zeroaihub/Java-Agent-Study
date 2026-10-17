package com.zero.ai.agentstudy.day17codeagent.tools;

import com.zero.ai.agentstudy.day17codeagent.core.model.CodeTaskContext;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * search_code：在仓库内按正则搜索。
 *
 * <p>Code Agent 找「这个符号在哪用到」几乎全靠它。<b>三个刻意的设计</b>：</p>
 * <ol>
 *     <li><b>结果必须限量</b>：在一个大仓库里搜索 {@code class} 能匹配几千行，
 *         全塞回上下文等于一次 DoS。这里默认截断到 20 条并明确告知被截断；</li>
 *     <li><b>正则错误要友好</b>：模型写错正则的概率不低，返回「正则语法错误」它会立刻改，
 *         抛异常则整个任务挂掉；</li>
 *     <li><b>输出带行号</b>：后续补丁需要「在第几行附近」这个信息。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class SearchTool implements Tool {

    /** 默认最大结果条数。 */
    public static final int DEFAULT_MAX_RESULTS = 20;

    private final VirtualFileSystem vfs;
    private final int maxResults;

    public SearchTool(VirtualFileSystem vfs) {
        this(vfs, DEFAULT_MAX_RESULTS);
    }

    public SearchTool(VirtualFileSystem vfs, int maxResults) {
        this.vfs = vfs;
        this.maxResults = maxResults;
    }

    @Override
    public String name() {
        return "search_code";
    }

    @Override
    public String description() {
        return "在仓库内按正则搜索代码片段，返回「文件:行号:内容」。";
    }

    @Override
    public List<String> parameters() {
        return List.of("pattern(必填)：Java 正则表达式", "dir(可选)：限定目录", "suffix(可选)：限定后缀");
    }

    @Override
    public ToolResult invoke(CodeTaskContext context, Map<String, String> args) {
        long start = System.currentTimeMillis();
        Map<String, String> safeArgs = args == null ? Map.of() : args;
        String patternText = safeArgs.get("pattern");
        if (patternText == null || patternText.isBlank()) {
            return ToolResult.failure(name(), "缺少参数 pattern", elapsed(start));
        }

        Pattern pattern;
        try {
            pattern = Pattern.compile(patternText);
        } catch (PatternSyntaxException e) {
            return ToolResult.failure(name(),
                    "正则表达式语法错误：" + patternText + "，" + e.getDescription()
                            + "。建议先确认特殊字符是否需要转义。",
                    elapsed(start));
        }

        String dir = safeArgs.getOrDefault("dir", "");
        String suffix = safeArgs.get("suffix");
        List<String> files = (suffix == null || suffix.isBlank())
                ? vfs.listFiles(dir)
                : vfs.listFilesEndingWith(dir, suffix);

        List<String> hits = new ArrayList<>();
        int total = 0;
        outer:
        for (String path : files) {
            com.zero.ai.agentstudy.day17codeagent.repo.RepoFile file = vfs.read(path);
            String[] lines = file.content().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (pattern.matcher(lines[i]).find()) {
                    total++;
                    if (hits.size() < maxResults) {
                        hits.add(path + ":" + (i + 1) + ": " + lines[i].strip());
                    } else {
                        break outer;
                    }
                }
            }
        }

        if (hits.isEmpty()) {
            return ToolResult.success(name(), "（无匹配）没有找到符合 " + patternText + " 的内容。",
                    elapsed(start));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("命中 ").append(total).append(" 处，展示前 ").append(hits.size()).append(" 条：\n");
        hits.forEach(h -> sb.append("  ").append(h).append('\n'));
        if (total > hits.size()) {
            sb.append("  ...(结果已截断，请缩小 pattern 范围)");
        }
        return ToolResult.success(name(), sb.toString().stripTrailing(), elapsed(start));
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}

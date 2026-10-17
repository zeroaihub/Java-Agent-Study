package com.zero.ai.agentstudy.day17codeagent.patch;

import com.zero.ai.agentstudy.day17codeagent.core.common.CodeException;
import com.zero.ai.agentstudy.day17codeagent.core.common.CodeErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 补丁解析器：把 LLM 的自由文本输出，解析成结构化的 {@link TextPatch} 列表。
 *
 * <h3>为什么必须要解析器</h3>
 * <p>LLM 不会严格按 JSON 输出。你让它「用 SEARCH/REPLACE 格式」，它会：</p>
 * <ul>
 *     <li>在代码块外面先啰嗦两句「好的，我修改了以下几点：」；</li>
 *     <li>把文件名写成 {@code `src/.../Money.java`} 带反引号；</li>
 *     <li>偶尔漏掉 {@code =======} 分隔符，或者多加几个；</li>
 *     <li>把路径写成绝对路径。</li>
 * </ul>
 * <p>所以解析器必须<b>容错而不失准</b>：能尽量救回来的一定救，但救不回来时明确报
 * {@code GENERATION_OUTPUT_INVALID}，而不是猜一个补丁出来。</p>
 *
 * <h3>支持的两种格式</h3>
 * <ol>
 *     <li><b>SEARCH/REPLACE 块</b>（推荐给 LLM 用，token 最省）：
 *     <pre>
 * File: src/main/java/demo/order/Money.java
 * &lt;&lt;&lt;&lt;&lt;&lt;&lt; SEARCH
 * old code
 * =======
 * new code
 * &gt;&gt;&gt;&gt;&gt;&gt;&gt; REPLACE
 *     </pre></li>
 *     <li><b>Unified Diff</b>（更贴近工程惯例，也便于人看）：会先被转成 SEARCH/REPLACE 语义再应用。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public final class PatchParser {

    private static final Pattern FILE_LINE = Pattern.compile(
            "(?:^|\\n)\\s*(?:#{0,6}\\s*)?(?:File|文件)\\s*[:：]\\s*`?([^`\\n]+?)`?\\s*(?:\\n|$)");
    private static final Pattern SEARCH_OPEN = Pattern.compile("^\\s*<{4,}\\s*SEARCH\\s*$");
    private static final Pattern SEP = Pattern.compile("^\\s*={4,}\\s*$");
    private static final Pattern REPLACE_CLOSE = Pattern.compile("^\\s*>{4,}\\s*(?:REPLACE)?\\s*$");
    private static final Pattern DIFF_HEADER = Pattern.compile("^(---|\\+\\+\\+)\\s+[ab]?/?(.+?)\\s*$");

    private PatchParser() {
    }

    /** 解析结果：成功解析出的补丁 + 无法处理的原始片段。 */
    public record ParsedPatches(List<TextPatch> patches, List<String> rejected) {
        public boolean hasRejected() {
            return !rejected.isEmpty();
        }
    }

    /**
     * 解析模型输出。
     *
     * @param llmOutput 模型原始输出
     * @param baseDir   若模型给出了绝对路径，用它尝试还原成仓库内相对路径（可为 null）
     */
    public static ParsedPatches parse(String llmOutput, String baseDir) {
        if (llmOutput == null || llmOutput.isBlank()) {
            throw new CodeException(CodeErrorCode.PATCH_EMPTY,
                    "模型输出为空，没有任何可应用的补丁");
        }
        List<TextPatch> patches = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        // ---- 尝试 Unified Diff ----
        String[] lines = llmOutput.split("\n", -1);
        List<TextPatch> fromDiff = parseUnifiedDiff(lines);
        if (!fromDiff.isEmpty()) {
            return new ParsedPatches(List.copyOf(fromDiff), List.of());
        }

        // ---- 尝试 SEARCH/REPLACE ----
        String currentFile = null;
        List<String> rejectBuffer = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            Matcher file = FILE_LINE.matcher(line);
            if (file.find()) {
                currentFile = normalizePath(file.group(1), baseDir);
                i++;
                continue;
            }
            if (SEARCH_OPEN.matcher(line).matches()) {
                if (currentFile == null) {
                    rejected.add("SEARCH 块缺少前置的 File 声明：" + describe(lines, i));
                    i = skipToEnd(lines, i);
                    continue;
                }
                StringBuilder search = new StringBuilder();
                i++;
                boolean sawSeparator = false;
                while (i < lines.length && !REPLACE_CLOSE.matcher(lines[i]).matches()) {
                    if (SEP.matcher(lines[i]).matches()) {
                        sawSeparator = true;
                        i++;
                        continue;
                    }
                    if (sawSeparator) {
                        break; // 分隔符之后就是替换内容
                    }
                    search.append(lines[i]).append('\n');
                    i++;
                }
                StringBuilder replace = new StringBuilder();
                while (i < lines.length && !REPLACE_CLOSE.matcher(lines[i]).matches()) {
                    replace.append(lines[i]).append('\n');
                    i++;
                }
                i++; // 跳过闭合标记
                if (!sawSeparator) {
                    rejected.add("SEARCH 块缺少 ======= 分隔符：" + currentFile);
                    continue;
                }
                patches.add(new TextPatch(
                        currentFile,
                        stripTrailingNewline(search.toString()),
                        stripTrailingNewline(replace.toString()),
                        null, null));
                continue;
            }
            // 既非文件声明也非补丁开始 → 当作模型的闲聊，忽略（但保留最后 200 字符便于诊断）
            if (!line.isBlank()) {
                rejectBuffer.add(line);
                if (rejectBuffer.size() > 50) {
                    rejectBuffer.remove(0);
                }
            }
            i++;
        }

        if (patches.isEmpty()) {
            throw new CodeException(CodeErrorCode.PATCH_PARSE_FAILED,
                    "未能从模型输出中解析出任何补丁。输出尾部：" + tail(rejectBuffer));
        }
        return new ParsedPatches(List.copyOf(patches), List.copyOf(rejected));
    }

    /** 路径归一化：去反引号、去前导斜杠、尝试去掉 baseDir 前缀。 */
    public static String normalizePath(String raw, String baseDir) {
        String p = raw.trim().replace("`", "").trim();
        p = p.replace('\\', '/');
        p = p.replaceAll("^[ab]/", "");
        if (baseDir != null && !baseDir.isBlank() && p.startsWith(baseDir)) {
            p = p.substring(baseDir.length());
        }
        p = p.replaceAll("^/+", "");
        return p;
    }

    // =====================================================================
    // Unified diff → TextPatch
    // =====================================================================

    private static List<TextPatch> parseUnifiedDiff(String[] lines) {
        List<TextPatch> patches = new ArrayList<>();
        String currentFile = null;
        List<String> hunk = new ArrayList<>();
        boolean inHunk = false;

        for (String line : lines) {
            Matcher header = DIFF_HEADER.matcher(line);
            if (header.matches()) {
                String path = normalizePath(header.group(2), null);
                if (currentFile != null && !hunk.isEmpty()) {
                    TextPatch patch = buildFromHunk(currentFile, hunk);
                    if (patch != null) {
                        patches.add(patch);
                    }
                }
                if (line.startsWith("---")) {
                    currentFile = path;
                    hunk = new ArrayList<>();
                    inHunk = false;
                }
                continue;
            }
            if (line.startsWith("@@")) {
                inHunk = true;
                continue;
            }
            if (currentFile != null && inHunk
                    && (line.startsWith(" ") || line.startsWith("+") || line.startsWith("-")
                    || line.startsWith("\\") || line.isEmpty())) {
                hunk.add(line);
            }
        }
        if (currentFile != null && !hunk.isEmpty()) {
            TextPatch patch = buildFromHunk(currentFile, hunk);
            if (patch != null) {
                patches.add(patch);
            }
        }
        return patches;
    }

    /**
     * 把一个 hunk 转成 SEARCH/REPLACE 语义。
     *
     * <p>SEARCH = 上下文行 + 被删行；REPLACE = 上下文行 + 新增行。
     * 这样即便模型给出的行号不准（极少准确），也能靠内容定位。</p>
     */
    private static TextPatch buildFromHunk(String file, List<String> hunk) {
        StringBuilder search = new StringBuilder();
        StringBuilder replace = new StringBuilder();
        for (String line : hunk) {
            if (line.startsWith("\\")) {
                continue; // "\ No newline at end of file"
            }
            // 关键容错：diff 里的「空上下文行」本应是单个空格 " "，
            // 但大量编辑器 / git 工具 / 聊天窗口会剥掉行尾空白，把它变成真正的空行。
            // 若在这里把它丢弃，SEARCH 片段就会缺少一个空行，补丁必然打不上。
            if (line.isEmpty()) {
                search.append('\n');
                replace.append('\n');
                continue;
            }
            if (line.startsWith(" ")) {
                search.append(line, 1, line.length()).append('\n');
                replace.append(line, 1, line.length()).append('\n');
            } else if (line.startsWith("-")) {
                search.append(line, 1, line.length()).append('\n');
            } else if (line.startsWith("+")) {
                replace.append(line, 1, line.length()).append('\n');
            }
        }
        if (search.length() == 0 && replace.length() == 0) {
            return null;
        }
        return new TextPatch(file,
                stripTrailingNewline(search.toString()),
                stripTrailingNewline(replace.toString()),
                null, null);
    }

    private static int skipToEnd(String[] lines, int i) {
        while (i < lines.length && !REPLACE_CLOSE.matcher(lines[i]).matches()) {
            i++;
        }
        return Math.min(i + 1, lines.length);
    }

    private static String stripTrailingNewline(String s) {
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    private static String describe(String[] lines, int i) {
        return i < lines.length ? lines[i].strip() : "(EOF)";
    }

    private static String tail(List<String> buffer) {
        String joined = String.join(" / ", buffer);
        return joined.length() > 200 ? joined.substring(joined.length() - 200) : joined;
    }
}

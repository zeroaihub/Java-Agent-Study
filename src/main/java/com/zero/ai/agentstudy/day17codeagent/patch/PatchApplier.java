package com.zero.ai.agentstudy.day17codeagent.patch;

import com.zero.ai.agentstudy.day17codeagent.core.common.CodeException;
import com.zero.ai.agentstudy.day17codeagent.core.common.CodeErrorCode;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;

import java.util.ArrayList;
import java.util.List;

/**
 * 补丁应用器：把 {@link TextPatch} 落到文件内容上。
 *
 * <h3>本模块最重要的一条原则</h3>
 * <blockquote>
 *     <b>打不上的补丁必须拒绝，绝不允许「猜着改」。</b>
 * </blockquote>
 *
 * <p>很多初版实现会做「模糊等价」匹配——找不到精确片段就退化成正则近似替换。
 * 这在 Demo 里成功率很高，但它把<b>确定性变成了概率</b>：一旦模糊匹配改到了错误的位置，
 * 代码看起来还在、测试也可能碰巧通过，问题会在几个月后以完全不相干的形式爆出来。
 * Code Agent 的底线是：<b>宁可明确失败让模型重写补丁，也不要静默改错。</b></p>
 *
 * <h3>四级匹配策略</h3>
 * <ol>
 *     <li>{@code EXACT} 逐字符精确匹配 —— 唯一可信的方式；</li>
 *     <li>{@code IGNORE_TRAILING_WHITESPACE} 忽略行尾空白 —— 模型输出常被 tail 掉的 Trailing 空白；</li>
 *     <li>{@code IGNORE_INDENT} 忽略缩进 —— 只在 <b>唯一命中</b> 时才允许，且必须标注；</li>
 *     <li>{@code NORMALIZE_WHITESPACE} 折叠所有连续空白 —— 最后的退路，必须提示人工复核。</li>
 * </ol>
 *
 * <p><b>关键约束</b>：模糊策略一旦导致「多于一处命中」，立即判定为
 * {@code PATCH_AMBIGUOUS}。宁可失败也不猜。</p>
 *
 * @author ZeroAi
 */
public class PatchApplier {

    private final boolean allowFuzzy;

    public PatchApplier(boolean allowFuzzy) {
        this.allowFuzzy = allowFuzzy;
    }

    /** 严格模式：只允许精确匹配。 */
    public static PatchApplier strict() {
        return new PatchApplier(false);
    }

    /** 容错模式：逐级降级，但会记录实际使用的策略。 */
    public static PatchApplier tolerant() {
        return new PatchApplier(true);
    }

    /**
     * 对一个文件的原文应用补丁。
     */
    public PatchResult apply(String original, TextPatch patch) {
        if (patch == null) {
            throw new CodeException(CodeErrorCode.INVALID_ARGUMENT, "patch 不能为空");
        }
        if (original == null) {
            return PatchResult.fail(patch.filePath(),
                    "目标文件不存在或为空", "请先确认文件路径是否正确，必要时先创建该文件", 0);
        }
        if (patch.search() == null || patch.search().isEmpty()) {
            return PatchResult.fail(patch.filePath(),
                    "补丁的 search 片段为空", "SEARCH/REPLACE 补丁必须带上要替换的原文片段", 0);
        }

        // ---- 第 1 级：精确匹配 ----
        int exactCount = countOccurrences(original, patch.search());

        // 先判定这级命中数是否「在语义上可接受」：多处命中 / occurrence 越界直接失败
        PatchResult bound = bindOccurrence(patch, exactCount);
        if (bound != null) {
            return bound;
        }
        if (exactCount > 0) {
            // 命中数为正：replaceNth 一定能替换成功，不会返回原文本
            return tryMatch(original, patch, exactCount);
        }
        // 到这里 exactCount == 0。
        // 曾经这里直接走 replaceNth，而它在找不到时「原样返回 original」，
        // 于是补丁没打上却被判定为成功——这正是本模块最该避免的静默失败。
        if (!allowFuzzy) {
            return explainFailure(patch, exactCount);
        }

        // ---- 第 2~4 级：逐级放宽空白 ----
        for (PatchResult.MatchStrategy strategy : List.of(
                PatchResult.MatchStrategy.IGNORE_TRAILING_WHITESPACE,
                PatchResult.MatchStrategy.IGNORE_INDENT,
                PatchResult.MatchStrategy.NORMALIZE_WHITESPACE)) {
            FuzzyMatch fuzzy = fuzzyMatch(original, patch.search(), strategy);
            if (fuzzy == null) {
                continue;
            }
            if (fuzzy.count() == 0) {
                continue;
            }
            if (fuzzy.count() > 1 && !patch.replaceAll()) {
                // 一旦放宽后变成多处命中，宁可失败也不猜
                return PatchResult.fail(patch.filePath(),
                        "补丁目标不唯一：在放宽空白后匹配到 " + fuzzy.count() + " 处",
                        "请在 search 片段里多带几行上下文使其唯一，" +
                                "或使用 occurrence 明确指定第几处",
                        fuzzy.count());
            }
            PatchResult fuzzyBound = bindOccurrence(patch, fuzzy.count());
            if (fuzzyBound != null) {
                return fuzzyBound;
            }
            String newContent = applyFuzzy(original, patch, strategy);
            if (newContent.equals(original)) {
                // 兜底防线：任何一级都不允许「内容没变却汇报成功」
                return PatchResult.fail(patch.filePath(),
                        "补丁未能应用：归一化后命中 " + fuzzy.count() + " 处，但内容未发生任何变化",
                        "请检查 replace 片段是否与 search 片段实质不同", fuzzy.count());
            }
            return PatchResult.ok(patch.filePath(), newContent, fuzzy.count(), strategy);
        }
        return explainFailure(patch, exactCount);
    }

    /** 直接对 VFS 里的文件应用补丁（读-改-写一步到位）。 */
    public PatchResult applyTo(VirtualFileSystem vfs, TextPatch patch) {
        var file = vfs.find(patch.filePath());
        if (file == null) {
            return PatchResult.fail(patch.filePath(),
                    "文件不存在：" + patch.filePath(),
                    "建议先调用 list_files 确认路径", 0);
        }
        PatchResult result = apply(file.content(), patch);
        if (result.success()) {
            vfs.write(patch.filePath(), result.content());
        }
        return result;
    }

    // =====================================================================

    private PatchResult tryMatch(String original, TextPatch patch, int count) {
        PatchResult bound = bindOccurrence(patch, count);
        if (bound != null) {
            return bound;
        }
        // occurrence == null 表示「必须唯一」，此时 bindOccurrence 已保证命中数为 1，
        // 所以这里用第 1 处替换；若错写成 Integer 拆箱会直接 NPE。
        int nth = patch.replaceAll() ? -1 : (patch.occurrence() == null ? 1 : patch.occurrence());
        String newContent = replaceNth(original, patch.search(), patch.replace(), nth);
        return PatchResult.ok(patch.filePath(), newContent, count,
                PatchResult.MatchStrategy.EXACT);
    }

    /**
     * 依据 occurrence 语义判定「当前命中数是否可以接受」。
     *
     * @return 非 null 表示应当直接返回该结果（成功或失败）
     */
    private PatchResult bindOccurrence(TextPatch patch, int count) {
        if (patch.replaceAll()) {
            return null; // 0 次命中对 replaceAll 也可接受与否，由 apply 统一处理
        }
        if (count > 1 && patch.occurrence() == null) {
            return PatchResult.fail(patch.filePath(),
                    "补丁目标不唯一：匹配到 " + count + " 处，而补丁未指定 occurrence",
                    "请在 search 片段里多带几行上下文使其唯一，或使用 occurrence 指定第几处",
                    count);
        }
        if (patch.occurrence() != null && patch.occurrence() > count) {
            return PatchResult.fail(patch.filePath(),
                    "occurrence=" + patch.occurrence() + " 超出实际命中数 " + count,
                    "请下调 occurrence 或检查文件是否已被上一步修改", count);
        }
        return null;
    }

    private PatchResult explainFailure(TextPatch patch, int exactCount) {
        String preview = patch.search().lines().findFirst().orElse("");
        return PatchResult.fail(patch.filePath(),
                "补丁未能应用：未匹配到 search 片段（精确匹配命中 " + exactCount + " 处）",
                "请重新读取文件当前内容，确认片段首行「" + preview.strip() + "」是否存在、"
                        + "缩进与空行是否一致；不要用记忆里的旧代码写补丁",
                exactCount);
    }

    private static String replaceNth(String text, String search, String replace, int nth) {
        String replacement = replace == null ? "" : replace;
        if (nth < 0) {
            return text.replace(search, replacement);
        }
        int index = -1;
        int from = 0;
        for (int i = 0; i < nth; i++) {
            index = text.indexOf(search, from);
            if (index < 0) {
                return text;
            }
            from = index + search.length();
        }
        int start = index;
        return text.substring(0, start) + replacement + text.substring(start + search.length());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }

    /** 在某种空白归一化下，统计命中窗口数（与 applyFuzzy 使用同一套判定，避免不一致）。 */
    private FuzzyMatch fuzzyMatch(String original, String search,
                                  PatchResult.MatchStrategy strategy) {
        List<String> originalLines = original.lines().toList();
        List<String> searchLines = search.lines().toList();
        if (searchLines.isEmpty()) {
            return null;
        }
        int count = 0;
        int from = 0;
        while (count < 1000) {
            int window = findWindow(originalLines, searchLines, strategy, from);
            if (window < 0) {
                break;
            }
            count++;
            from = window + 1;
        }
        return new FuzzyMatch(count);
    }

    /**
     * 在模糊匹配下完成替换。
     *
     * <p>做法是<b>逐行窗口匹配</b>而不是字符级映射：把原文与 search 都按
     * 当前策略做行归一化，找到连续命中的行窗口，再用「带上原文首行缩进」的
     * replace 行替换该窗口。这样既容忍了缩进/行尾空白差异，又不会破坏原文其余部分。</p>
     */
    private static String applyFuzzy(String original, TextPatch patch,
                                     PatchResult.MatchStrategy strategy) {
        List<String> originalLines = original.lines().toList();
        List<String> searchLines = patch.search().lines().toList();
        List<String> replaceLines = (patch.replace() == null || patch.replace().isEmpty())
                ? List.of()
                : patch.replace().lines().toList();

        int window = findWindow(originalLines, searchLines, strategy, 0);
        if (window < 0) {
            return original;
        }
        String indent = leadingWhitespace(originalLines.get(window));
        List<String> aligned = replaceLines.stream()
                .map(line -> indent + stripIndent(line))
                .toList();

        List<String> rebuilt = new ArrayList<>();
        rebuilt.addAll(originalLines.subList(0, window));
        rebuilt.addAll(aligned);
        rebuilt.addAll(originalLines.subList(Math.min(window + searchLines.size(),
                originalLines.size()), originalLines.size()));
        return String.join("\n", rebuilt);
    }

    /**
     * 在原文行序列中查找匹配的连续行窗口。
     *
     * @param fromIndex 从第几行开始找
     * @return 命中起始行号，未命中返回 -1
     */
    static int findWindow(List<String> originalLines, List<String> searchLines,
                          PatchResult.MatchStrategy strategy, int fromIndex) {
        if (searchLines.isEmpty()) {
            return -1;
        }
        outer:
        for (int i = fromIndex; i <= originalLines.size() - searchLines.size(); i++) {
            for (int j = 0; j < searchLines.size(); j++) {
                String a = normalizeLine(originalLines.get(i + j), strategy);
                String b = normalizeLine(searchLines.get(j), strategy);
                if (!a.equals(b)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** 单行归一化。 */
    private static String normalizeLine(String line, PatchResult.MatchStrategy strategy) {
        return switch (strategy) {
            case EXACT -> line;
            case IGNORE_TRAILING_WHITESPACE -> stripTrailing(line);
            case IGNORE_INDENT -> line.strip();
            case NORMALIZE_WHITESPACE -> line.strip().replaceAll("\\s+", " ");
        };
    }

    private static String leadingWhitespace(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return s.substring(0, i);
    }

    private static String stripIndent(String s) {
        return s.stripLeading();
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) {
            end--;
        }
        return s.substring(0, end);
    }

    /** 模糊匹配的命中次数（不含具体位置，足以支撑「是否唯一」的判定）。 */
    private record FuzzyMatch(int count) {
    }
}

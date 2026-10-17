package com.zero.ai.agentstudy.day17codeagent.patch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified Diff 生成器（基于 LCS 的行级差异）。
 *
 * <p>Code Agent 不能只把「改完了」告诉用户，必须给出<b>可审阅的差异</b>。
 * 人能不能信任这个 Agent，往往取决于这一步做得好不好——
 * 一个把 300 行文件整份重写的 Agent，和一个给出 8 行 diff 的 Agent，给人的信任感完全不同。</p>
 *
 * <p>这里用经典 LCS（最长公共子序列）做行级 diff，零依赖，够用。
 * 生产中可以直接换成 java-diff-utils。</p>
 *
 * @author ZeroAi
 */
public final class UnifiedDiff {

    private UnifiedDiff() {
    }

    /** 一条差异行。 */
    public record DiffLine(Type type, int oldLine, int newLine, String content) {
        public enum Type { CONTEXT, ADD, REMOVE }

        public String marker() {
            return switch (type) {
                case CONTEXT -> " ";
                case ADD -> "+";
                case REMOVE -> "-";
            };
        }

        @Override
        public String toString() {
            return marker() + content;
        }
    }

    /** 生成标准 unified diff 文本。 */
    public static String generate(String filePath, String before, String after) {
        List<DiffLine> lines = diff(before, after);
        return generate(filePath, lines);
    }

    public static String generate(String filePath, List<DiffLine> lines) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(filePath).append('\n');
        sb.append("+++ b/").append(filePath).append('\n');
        if (lines.isEmpty()) {
            return sb.toString();
        }
        sb.append("@@ -").append(countOf(lines, DiffLine.Type.CONTEXT, DiffLine.Type.REMOVE))
                .append(" +").append(countOf(lines, DiffLine.Type.CONTEXT, DiffLine.Type.ADD))
                .append(" @@\n");
        for (DiffLine line : lines) {
            sb.append(line.marker()).append(line.content()).append('\n');
        }
        return sb.toString();
    }

    private static int countOf(List<DiffLine> lines, DiffLine.Type... types) {
        return (int) lines.stream().filter(l -> Arrays.asList(types).contains(l.type())).count();
    }

    /**
     * LCS 行级 diff。
     *
     * <p>时间复杂度 O(n*m)，对单文件足够；超大文件生产应换成 Myers / Histogram 算法。</p>
     */
    public static List<DiffLine> diff(String before, String after) {
        List<String> oldLines = before == null ? List.of() : before.lines().toList();
        List<String> newLines = after == null ? List.of() : after.lines().toList();

        int n = oldLines.size();
        int m = newLines.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = oldLines.get(i).equals(newLines.get(j))
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<DiffLine> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        int oldNo = 1;
        int newNo = 1;
        while (i < n && j < m) {
            if (oldLines.get(i).equals(newLines.get(j))) {
                result.add(new DiffLine(DiffLine.Type.CONTEXT, oldNo++, newNo++, oldLines.get(i)));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                result.add(new DiffLine(DiffLine.Type.REMOVE, oldNo++, newNo, oldLines.get(i)));
                i++;
            } else {
                result.add(new DiffLine(DiffLine.Type.ADD, oldNo, newNo++, newLines.get(j)));
                j++;
            }
        }
        while (i < n) {
            result.add(new DiffLine(DiffLine.Type.REMOVE, oldNo++, newNo, oldLines.get(i++)));
        }
        while (j < m) {
            result.add(new DiffLine(DiffLine.Type.ADD, oldNo, newNo++, newLines.get(j++)));
        }
        return List.copyOf(result);
    }

    /** 压缩上下文：只保留变更行附近的 context 行（默认 ±3）。 */
    public static String generateCompact(String filePath, String before, String after) {
        return generateCompact(filePath, before, after, 3);
    }

    public static String generateCompact(String filePath, String before, String after, int contextLines) {
        List<DiffLine> lines = diff(before, after);
        List<DiffLine> kept = new ArrayList<>();
        for (int k = 0; k < lines.size(); k++) {
            DiffLine line = lines.get(k);
            if (line.type() != DiffLine.Type.CONTEXT) {
                kept.add(line);
                continue;
            }
            boolean near = false;
            for (int d = Math.max(0, k - contextLines); d < Math.min(lines.size(), k + contextLines + 1); d++) {
                if (lines.get(d).type() != DiffLine.Type.CONTEXT) {
                    near = true;
                    break;
                }
            }
            if (near) {
                kept.add(line);
            }
        }
        return generate(filePath, kept);
    }

    /** 统计变更行数。 */
    public static Map<String, Integer> stat(String before, String after) {
        List<DiffLine> lines = diff(before, after);
        Map<String, Integer> stat = new LinkedHashMap<>();
        stat.put("added", countOf(lines, DiffLine.Type.ADD));
        stat.put("removed", countOf(lines, DiffLine.Type.REMOVE));
        stat.put("changed", countOf(lines, DiffLine.Type.ADD, DiffLine.Type.REMOVE));
        return stat;
    }
}

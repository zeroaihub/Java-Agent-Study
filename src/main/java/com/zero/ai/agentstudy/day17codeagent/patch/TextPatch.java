package com.zero.ai.agentstudy.day17codeagent.patch;

import java.util.List;

/**
 * 一个「搜索 / 替换」补丁块。
 *
 * <h3>为什么选 SEARCH/REPLACE 而不是整文件重写</h3>
 * <table>
 *     <tr><th></th><th>整文件重写</th><th>SEARCH/REPLACE</th></tr>
 *     <tr><td>输出体积</td><td>整个文件 300 行</td><td>改动的 8 行</td></tr>
 *     <tr><td>token 成本</td><td>高</td><td>低</td></tr>
 *     <tr><td>出错后果</td><td><b>模型「顺手改良」无关代码</b></td><td>只动指定片段</td></tr>
 *     <tr><td>冲突定位</td><td>难以比对「它改了哪」</td><td>一目了然</td></tr>
 * </table>
 *
 * <p><b>整文件重写最危险的一点</b>：模型在「重写」时会无意识地修改风格、删掉它没理解的代码，
 * code review 时很难逐行发现。SEARCH/REPLACE 把改动面强制收窄到明确声明的片段。</p>
 *
 * @param filePath   目标文件相对路径
 * @param search     要被替换的原文片段（必须与文件内容<b>精确对应</b>）
 * @param replace    替换后的新片段
 * @param occurrence 第几次出现的片段：null 表示「必须唯一」，1..n 表示第 n 处，-1 表示全部替换
 * @param note       模型给出的修改说明（会进 PR 描述与审计日志）
 * @author ZeroAi
 */
public record TextPatch(
        String filePath,
        String search,
        String replace,
        Integer occurrence,
        String note
) {

    /** 唯一命中：最常用的构造方式。 */
    public static TextPatch unique(String filePath, String search, String replace) {
        return new TextPatch(filePath, search, replace, null, null);
    }

    public static TextPatch all(String filePath, String search, String replace) {
        return new TextPatch(filePath, search, replace, -1, null);
    }

    public static TextPatch nth(String filePath, String search, String replace, int nth) {
        return new TextPatch(filePath, search, replace, nth, null);
    }

    public TextPatch withNote(String newNote) {
        return new TextPatch(filePath, search, replace, occurrence, newNote);
    }

    public boolean replaceAll() {
        return occurrence != null && occurrence == -1;
    }

    /** 新增行 / 删除行数量，用于生成 diff 摘要与 PR 描述。 */
    public PatchStats stats() {
        List<String> oldLines = search == null ? List.of() : search.lines().toList();
        List<String> newLines = replace == null ? List.of() : replace.lines().toList();
        return new PatchStats(oldLines.size(), newLines.size());
    }

    /**
     * 补丁体量统计。
     *
     * @param removedLines 被删除行数
     * @param addedLines   被新增行数
     */
    public record PatchStats(int removedLines, int addedLines) {
        public int changed() {
            return removedLines + addedLines;
        }

        @Override
        public String toString() {
            return "+" + addedLines + " -" + removedLines;
        }
    }

    @Override
    public String toString() {
        return "TextPatch{" + filePath + ", " + stats() + "}";
    }
}

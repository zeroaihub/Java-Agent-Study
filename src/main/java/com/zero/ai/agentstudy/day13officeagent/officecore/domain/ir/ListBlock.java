package com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir;

import java.util.List;

/**
 * 列表块（ListBlock）。
 *
 * <p>表达有序（数字编号）或无序（项目符号）列表——会议纪要的"待办事项"、周报的
 * "本周完成项"都天然是列表结构。每个列表项（{@link ListItem}）携带缩进层级 {@code indent}，
 * 从而支持多级嵌套列表；项内容以 {@link Run} 富文本表达。</p>
 *
 * @param ordered {@code true} 表示有序列表，{@code false} 表示无序列表
 * @param items   列表项，永不为 {@code null}
 * @author zero
 */
public record ListBlock(boolean ordered, List<ListItem> items) implements Block {

    public ListBlock {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 快捷工厂：由纯文本行构造无序列表。 */
    public static ListBlock unordered(List<String> lines) {
        return new ListBlock(false, lines.stream()
                .map(line -> new ListItem(List.of(Run.of(line)), 0))
                .toList());
    }

    /** 快捷工厂：由纯文本行构造有序列表。 */
    public static ListBlock ordered(List<String> lines) {
        return new ListBlock(true, lines.stream()
                .map(line -> new ListItem(List.of(Run.of(line)), 0))
                .toList());
    }

    @Override
    public BlockType type() {
        return BlockType.LIST;
    }

    /**
     * 列表项值对象。
     *
     * @param runs   列表项富文本内容
     * @param indent 缩进层级，0 为顶层，用于多级嵌套
     * @author zero
     */
    public record ListItem(List<Run> runs, int indent) {

        public ListItem {
            runs = runs == null ? List.of() : List.copyOf(runs);
            indent = Math.max(0, indent);
        }

        /** 快捷工厂：顶层纯文本列表项。 */
        public static ListItem of(String text) {
            return new ListItem(List.of(Run.of(text)), 0);
        }
    }
}
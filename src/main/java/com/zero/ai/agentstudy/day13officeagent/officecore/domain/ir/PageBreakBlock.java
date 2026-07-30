package com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir;

/**
 * 分页符块（PageBreakBlock）。
 *
 * <p>显式的分页语义——在 Word/PDF 中强制另起一页，在 PPT 中表示"切到下一张幻灯片"。
 * 生成周报 PPT 时，"每个议题一页"就是通过在议题之间插入 PageBreakBlock 实现的。</p>
 *
 * <p>这是一个无状态的标记块，因此以单例形式提供，避免重复创建对象。</p>
 *
 * @author zero
 */
public record PageBreakBlock() implements Block {

    private static final PageBreakBlock INSTANCE = new PageBreakBlock();

    /**
     * 获取分页符块单例。
     *
     * @return 共享的分页符块实例
     */
    public static PageBreakBlock instance() {
        return INSTANCE;
    }

    @Override
    public BlockType type() {
        return BlockType.PAGE_BREAK;
    }
}
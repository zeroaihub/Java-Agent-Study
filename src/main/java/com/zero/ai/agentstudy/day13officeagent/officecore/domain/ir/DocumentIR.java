package com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档中间表示（Document Intermediate Representation, IR）——整个 Office Agent 的语义中枢。
 *
 * <p><b>为什么需要 IR？</b> 如果让大模型直接生成 .docx/.pptx 二进制，或直接拼接 POI API 调用，
 * 会陷入三个致命陷阱：(1) 格式与内容强耦合，改一次排版要重写生成逻辑；(2) 无法一份内容
 * 多格式输出（Word/PDF/PPT 各写一套）；(3) 大模型幻觉直接写进二进制，无法在渲染前校验。</p>
 *
 * <p><b>IR 的解法：</b> 把"文档是什么"抽象成一棵与格式无关的语义树——一份 {@code DocumentMetadata}
 * 加一个有序的 {@link Block} 列表。生成阶段只产出 IR（可校验、可修改、可结构化输出），
 * 渲染阶段再由不同的 {@code DocumentRenderer} 适配器把同一棵 IR 树翻译成 Word / Excel / PPT / PDF /
 * Markdown。这就是"内容与格式解耦"的落地形态，也是 Spring AI 结构化输出的目标类型。</p>
 *
 * <p>本类是不可变聚合根：所有修改都通过 {@link Builder} 或 {@code with*} 方法产生新实例，
 * 从而天然线程安全，可在 Virtual Threads 并行渲染多格式时安全共享。</p>
 *
 * @author zero
 */
public final class DocumentIR {

    private final DocumentMetadata metadata;
    private final List<Block> blocks;

    private DocumentIR(DocumentMetadata metadata, List<Block> blocks) {
        this.metadata = metadata == null ? DocumentMetadata.of("", "") : metadata;
        this.blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    /**
     * 直接构造一个 IR。
     *
     * @param metadata 文档元数据
     * @param blocks   有序块列表
     * @return 不可变的文档 IR
     */
    public static DocumentIR of(DocumentMetadata metadata, List<Block> blocks) {
        return new DocumentIR(metadata, blocks);
    }

    /**
     * 创建一个 Builder。
     *
     * @return 新的构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /** 文档元数据。 */
    public DocumentMetadata metadata() {
        return metadata;
    }

    /** 有序块列表（不可变）。 */
    public List<Block> blocks() {
        return blocks;
    }

    /** 块总数。 */
    public int blockCount() {
        return blocks.size();
    }

    /**
     * 派生一个替换了元数据的新 IR。
     *
     * @param newMetadata 新元数据
     * @return 新的文档 IR
     */
    public DocumentIR withMetadata(DocumentMetadata newMetadata) {
        return new DocumentIR(newMetadata, blocks);
    }

    /**
     * 派生一个在末尾追加若干块的新 IR。
     *
     * @param more 要追加的块
     * @return 新的文档 IR
     */
    public DocumentIR append(List<Block> more) {
        List<Block> merged = new ArrayList<>(this.blocks);
        if (more != null) {
            merged.addAll(more);
        }
        return new DocumentIR(metadata, merged);
    }

    /**
     * 文档 IR 构建器——以流式 API 逐块搭建文档，屏蔽 List 拼装细节。
     */
    public static final class Builder {

        private DocumentMetadata metadata = DocumentMetadata.of("", "");
        private final List<Block> blocks = new ArrayList<>();

        /** 设置文档元数据。 */
        public Builder metadata(DocumentMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        /** 追加任意块。 */
        public Builder block(Block block) {
            if (block != null) {
                this.blocks.add(block);
            }
            return this;
        }

        /** 便捷方法：追加一级标题。 */
        public Builder heading(int level, String text) {
            return block(HeadingBlock.of(level, text));
        }

        /** 便捷方法：追加正文段落。 */
        public Builder paragraph(String text) {
            return block(ParagraphBlock.of(text));
        }

        /** 便捷方法：追加分页符。 */
        public Builder pageBreak() {
            return block(PageBreakBlock.instance());
        }

        /** 构建不可变的文档 IR。 */
        public DocumentIR build() {
            return new DocumentIR(metadata, blocks);
        }
    }
}
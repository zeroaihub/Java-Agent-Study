package com.zero.ai.agentstudy.day05rag.mycode.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Chunk：文档被切分后的「一小段文本」。
 *
 * <p>为什么需要这个类？
 * 长文档不能整篇做 Embedding（太长会丢语义、也超模型输入上限），
 * 所以要先切成一段段小文本。每一段就是一个 Chunk。
 *
 * <p>它只描述「切分后的纯文本 + 它来自哪个文档」，还没有向量。
 * 向量化之后会升级成 {@link EmbeddedChunk}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Chunk {

    /** 这段文本来自哪个文档的标题（用于回答时标注出处） */
    private String docTitle;

    /** 这是该文档里的第几段（从 0 开始，用于排序 / 出处定位） */
    private int index;

    /** 切分后的纯文本内容 */
    private String content;
}
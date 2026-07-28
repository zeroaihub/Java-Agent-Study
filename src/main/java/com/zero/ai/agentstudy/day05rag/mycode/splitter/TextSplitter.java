package com.zero.ai.agentstudy.day05rag.mycode.splitter;

import com.zero.ai.agentstudy.day05rag.mycode.entity.Chunk;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * TextSplitter：把一篇长文本切成一段段小 Chunk。
 *
 * <p>为什么需要切分？
 * <ul>
 *   <li>Embedding 模型对输入长度有上限，整篇太长会被截断。</li>
 *   <li>召回是「以块为单位」的，块太大 → 召回到的无关内容多、噪音大；
 *       块太小 → 语义被切碎、上下文不完整。</li>
 * </ul>
 *
 * <p>本类采用最常见、最好理解的「定长滑动窗口 + 重叠」策略：
 * <ul>
 *   <li>chunkSize：每块目标字数（如 300）。</li>
 *   <li>overlap：相邻块重叠字数（如 60），防止把一句话从中间切断导语义丢失。</li>
 * </ul>
 * 切分前先按段落规整，尽量在自然边界处断开，保证块可读。
 */
@Component
public class TextSplitter {

    /** 每块目标字数 */
    private static final int CHUNK_SIZE = 300;

    /** 相邻块重叠字数（约为 chunkSize 的 15%~20%） */
    private static final int OVERLAP = 60;

    /**
     * 把文档正文切成 Chunk 列表。
     *
     * @param docTitle 文档标题（写入每个 Chunk，用于回答时标注出处）
     * @param content  文档正文
     * @return 切分后的 Chunk 列表
     */
    public List<Chunk> split(String docTitle, String content) {
        List<Chunk> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return chunks;
        }

        // 1. 规整文本：把多余的空白、换行压成单个空格，便于按长度切
        String normalized = content.replaceAll("\\s+", " ").trim();

        // 2. 滑动口切分：每次取 CHUNK_SIZE 个字，下一块回退 OVERLAP 个字
        int step = CHUNK_SIZE - OVERLAP; // 每次窗口前进的步长
        int index = 0;
        for (int start = 0; start < normalized.length(); start += step) {
            int end = Math.min(start + CHUNK_SIZE, normalized.length());
            String piece = normalized.substring(start, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(new Chunk(docTitle, index++, piece));
            }
            // 已经切到末尾，退出（避免最后重叠部分产生重复块）
            if (end == normalized.length()) {
                break;
            }
        }
        return chunks;
    }

    /**
     * 【推荐】用 Spring AI 官方的 {@link TokenTextSplitter} 来切分，而不是自己手写。
     *
     * <p>为什么用它？
     * <ul>
     *   <li>它按「Token 数」而非「字符数」切，和 Embedding/LLM 真正的计费与输入上限口径一致，更精准；</li>
     *   <li>会尽量在句子边界断开，块更完整、可读；</li>
     *   <li>官方维护、生产可用，无需自己处理各种边界情况。</li>
     * </ul>
     *
     * <p>它的输入/输出都是 Spring AI 的 {@link Document}，所以本方法做两件事：
     * <ol>
     *   <li>把正文包成一个 Document 交给 TokenTextSplitter 切；</li>
     *   <li>把切出来的 Document 列表转回项目自己的 {@link Chunk}，方便后续统一处理。</li>
     * </ol>
     *
     * @param docTitle 文档标题（写入每个 Chunk，用于回答时标注出处）
     * @param content  文档正文
     * @return 切分后的 Chunk 列表
     */
    public List<Chunk> splitBySpringAi(String docTitle, String content) {
        List<Chunk> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return chunks;
        }

        // 1. 构造 TokenTextSplitter。
        //    官方默认配置为 (目标块 token 数=800, 最小块字符数=350, ...)，
        //    但本项目文档一篇才 1000 字左右，用 800 token 会导致整篇几乎切不开、块过大。
        //    因此显式传入更小的参数，参数含义：
        //      defaultChunkSize        目标块 token 数（1 token≈汉字约 1~1.5 字）
        //      minChunkSizeChars       句子拼装到该字符数后就考虑断句成块
        //      minChunkLengthToEmbed   小于该长度的块直接丢弃（过滤空/极短块）
        //      maxNumChunks            单篇最多切出的块数
        //      keepSeparator           是否保留换行等分隔符
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(100)
                .withMinChunkSizeChars(50)
                .withMinChunkSizeChars(5)
                .withMaxNumChunks(10000).withKeepSeparator(true).build();

        // 2. 把整篇正文包成一个 Document 丢给它切分（按 token 切、尽量在句子边界断开）。
        List<Document> documents = splitter.apply(List.of(new Document(content)));

        // 3. 把 Spring AI 的 Document 转回项目的 Chunk（补上标题与序号）。
        int index = 0;
        for (Document doc : documents) {
            String piece = doc.getText();
            if (piece != null && !piece.isBlank()) {
                chunks.add(new Chunk(docTitle, index++, piece.trim()));
            }
        }
        return chunks;
    }
}
package com.zero.ai.agentstudy.day15knowledgeplatform.document.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;

/**
 * 文档版本号（值对象，不可变）。
 *
 * <p>为什么文档需要版本？因为企业知识会更新。没有版本会带来两个灾难：</p>
 * <ol>
 *     <li><b>无法回溯</b>：用户拿到一个答案，指回的原文已经被改得面目全非，溯源失效。</li>
 *     <li><b>向量漂移</b>：文档更新后新旧 Chunk 混在向量库里，检索到过期知识，
 *         而且你根本不知道哪些向量是旧的、该删哪些。</li>
 * </ol>
 *
 * <p>版本号从 1 开始，每次重新上传同一份文档自增。
 * 第 4 章的 sync 模块会基于版本号做「旧版本 Chunk 的级联删除」。</p>
 *
 * @author ZeroAi
 */
public record DocumentVersion(int value) {

    /** 首个版本 */
    public static final DocumentVersion FIRST = new DocumentVersion(1);

    public DocumentVersion {
        if (value < 1) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT,
                    "文档版本号必须 >= 1，实际为 " + value);
        }
    }

    public static DocumentVersion of(int value) {
        return new DocumentVersion(value);
    }

    /**
     * 下一个版本（自增）。
     */
    public DocumentVersion next() {
        return new DocumentVersion(value + 1);
    }

    /**
     * 是否比 other 更新。
     */
    public boolean isNewerThan(DocumentVersion other) {
        return this.value > other.value;
    }

    @Override
    public String toString() {
        return "v" + value;
    }
}

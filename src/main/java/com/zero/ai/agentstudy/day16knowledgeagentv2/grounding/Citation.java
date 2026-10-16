package com.zero.ai.agentstudy.day16knowledgeagentv2.grounding;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

/**
 * 引用来源（Chapter 09）。
 *
 * <p>企业问答与闲聊 AI 最大的区别：答案必须<b>可溯源</b>。每一条结论都要能指回具体的
 * 文档片段，用户点开就能核对原文。没有引用的答案，在企业场景等同于「不可信」。</p>
 *
 * @param chunkId    被引用的片段
 * @param documentId 所属文档
 * @param title      文档标题
 * @param snippet    引用的原文摘录
 * @author ZeroAi
 */
public record Citation(String chunkId, String documentId, String title, String snippet) {

    public Citation {
        AgentException.requireText(chunkId, "chunkId");
        AgentException.requireText(documentId, "documentId");
    }

    @Override
    public String toString() {
        return "[" + title + "#" + chunkId + "] " + snippet;
    }
}
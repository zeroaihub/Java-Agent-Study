package com.zero.ai.agentstudy.day4memory.chapter5;

import java.util.List;

/**
 * 教学版 ChatMemory 抽象。
 *
 * 这个接口模拟 Spring AI / LangChain4j 的核心设计：
 * 业务代码依赖抽象，不依赖具体存储。
 */
public interface FrameworkChatMemory {

    void add(String conversationId, FrameworkMessage message);

    List<FrameworkMessage> get(String conversationId);

    void clear(String conversationId);
}


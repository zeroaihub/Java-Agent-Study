package com.zero.ai.agentstudy.day4memory.chapter5;

import java.util.List;

/**
 * 框架设计说明。
 */
public record FrameworkDesignResponse(
        String asciiDiagram,
        List<String> springAiConcepts,
        List<String> langChain4jConcepts,
        List<String> sharedDesignIdeas
) {
}


package com.zero.ai.agentstudy.day4memory.chapter7;

import java.util.List;

/**
 * Memory 与 RAG 协同说明。
 */
public record RagCoordinationResponse(
        String asciiDiagram,
        List<String> priorityRules,
        List<String> implementationSteps,
        String promptTemplate
) {
}


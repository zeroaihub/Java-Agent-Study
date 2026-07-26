package com.zero.ai.agentstudy.day4memory.chapter7;

import java.util.List;

/**
 * 企业最佳实践总览。
 */
public record BestPracticeResponse(
        List<MemoryLifecyclePolicy> lifecyclePolicies,
        RagCoordinationResponse ragCoordination,
        List<String> productionChecklist
) {
}


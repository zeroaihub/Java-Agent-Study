package com.zero.ai.agentstudy.day4memory.chapter4;

import java.util.List;

/**
 * 用户画像 schema 响应。
 */
public record ProfileSchemaResponse(
        String asciiDiagram,
        List<ProfileFieldRule> fieldRules,
        String mysqlDdl
) {
}


package com.zero.ai.agentstudy.day15knowledgeplatform.chunk.port;

import java.util.List;

/**
 * 切分策略端口。
 *
 * <p>刻意把接口设计成 {@code String -> List<String>}：<b>策略只负责「把文本切成几段」，
 * 不负责造 Chunk 对象</b>。</p>
 *
 * <p>为什么要这么切？因为「装配 Chunk」涉及 ID 生成、序号、元数据富化、租户注入——
 * 这些与切分算法无关。让策略只管算法，策略就是纯函数：
 * 无状态、易测试、可随意替换。</p>
 *
 * @author ZeroAi
 */
public interface ChunkStrategy {

    /**
     * 策略名称（用于配置选择与日志）。
     */
    String name();

    /**
     * 切分文本。
     *
     * @param text 待切分全文
     * @return 切片文本列表，保持原文顺序，不含空串
     */
    List<String> split(String text);
}

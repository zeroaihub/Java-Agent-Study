package com.zero.ai.agentstudy.day4memory.chapter3;

import com.zero.ai.agentstudy.back.model.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第三章：Chat Memory 工作原理。
 *
 * 本章不调用真实 LLM，而是模拟“每轮对话如何组装 Prompt”，方便观察：
 * - 完整历史为什么越来越长
 * - 滑动窗口如何控制长度
 * - 摘要压缩如何降低 token 成本
 */
@Service
@RequiredArgsConstructor
public class Chapter3MemoryService {

    private static final int WINDOW_MESSAGES = 10;
    private static final int COMPRESS_AFTER_MESSAGES = 12;
    private static final int RECENT_MESSAGES_AFTER_COMPRESSION = 8;

    private final SimpleTokenEstimator tokenEstimator;

    private final Map<String, List<ChatMessage>> sessionHistory =
            Collections.synchronizedMap(new LinkedHashMap<>());

    private final Map<String, String> sessionSummary =
            Collections.synchronizedMap(new LinkedHashMap<>());

    public List<MemoryStrategyView> strategies() {
        return List.of(
                MemoryStrategyView.from(MemoryStrategy.FULL_HISTORY),
                MemoryStrategyView.from(MemoryStrategy.MESSAGE_WINDOW),
                MemoryStrategyView.from(MemoryStrategy.SUMMARY_COMPRESSION)
        );
    }

    public MemoryGrowthReport growthReport() {
        List<MemoryGrowthReport.RoundCost> costs = new ArrayList<>();
        int averageUserTokens = 30;
        int averageAssistantTokens = 60;
        int systemTokens = 40;
        for (int round = 1; round <= 8; round++) {
            int messages = 1 + round * 2;
            int tokens = systemTokens + round * (averageUserTokens + averageAssistantTokens);
            costs.add(new MemoryGrowthReport.RoundCost(
                    round,
                    messages,
                    tokens,
                    "第 " + round + " 轮需要重新发送 system + 前面所有 user/assistant 消息"
            ));
        }
        return new MemoryGrowthReport(asciiDiagram(), costs,
                "聊天越久越贵，是因为每次请求都要重新携带历史上下文，模型需要重新读取这些 token。");
    }

    public Chapter3ChatResponse fullHistory(String sessionId, String message) {
        return chat(sessionId, message, MemoryStrategy.FULL_HISTORY);
    }

    public Chapter3ChatResponse messageWindow(String sessionId, String message) {
        return chat(sessionId, message, MemoryStrategy.MESSAGE_WINDOW);
    }

    public Chapter3ChatResponse summaryCompression(String sessionId, String message) {
        return chat(sessionId, message, MemoryStrategy.SUMMARY_COMPRESSION);
    }

    public List<ChatMessage> history(String sessionId) {
        requireText(sessionId, "sessionId");
        return copyHistory(sessionId);
    }

    public void clear(String sessionId) {
        requireText(sessionId, "sessionId");
        sessionHistory.remove(sessionId);
        sessionSummary.remove(sessionId);
    }

    private Chapter3ChatResponse chat(String sessionId, String message, MemoryStrategy strategy) {
        requireText(sessionId, "sessionId");
        requireText(message, "message");

        List<ChatMessage> history = getOrCreateHistory(sessionId);
        synchronized (history) {
            history.add(userMessage(message));
            String answer = mockAssistantAnswer(strategy, history.size());
            history.add(assistantMessage(answer));

            if (strategy == MemoryStrategy.SUMMARY_COMPRESSION) {
                updateSummaryIfNeeded(sessionId, history);
            }

            List<ChatMessage> prompt = buildPrompt(sessionId, history, strategy);
            return new Chapter3ChatResponse(
                    strategy.getCode(),
                    sessionId,
                    answer,
                    history.size(),
                    prompt.size(),
                    tokenEstimator.estimate(prompt),
                    new ArrayList<>(history),
                    prompt
            );
        }
    }

    private List<ChatMessage> buildPrompt(String sessionId, List<ChatMessage> history, MemoryStrategy strategy) {
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(systemMessage("""
                你是 Day4 第三章 Chat Memory Demo。
                请观察本次 prompt 中携带了多少历史消息，以及估算 token 如何变化。
                """));

        if (strategy == MemoryStrategy.FULL_HISTORY) {
            prompt.addAll(history);
            return prompt;
        }

        if (strategy == MemoryStrategy.MESSAGE_WINDOW) {
            prompt.addAll(tail(history, WINDOW_MESSAGES));
            return prompt;
        }

        String summary = sessionSummary.get(sessionId);
        if (StringUtils.hasText(summary)) {
            prompt.add(systemMessage("历史摘要：" + summary));
        }
        prompt.addAll(tail(history, RECENT_MESSAGES_AFTER_COMPRESSION));
        return prompt;
    }

    private void updateSummaryIfNeeded(String sessionId, List<ChatMessage> history) {
        if (history.size() <= COMPRESS_AFTER_MESSAGES) {
            return;
        }

        List<ChatMessage> oldMessages = history.subList(0, history.size() - RECENT_MESSAGES_AFTER_COMPRESSION);
        StringBuilder summary = new StringBuilder();
        summary.append("本会话已经进行了 ").append(history.size() / 2).append(" 轮对话；");
        for (ChatMessage message : oldMessages) {
            if ("user".equals(message.getRole())) {
                summary.append("用户曾提到：")
                        .append(shorten(message.getContent(), 30))
                        .append("；");
            }
        }
        sessionSummary.put(sessionId, summary.toString());
    }

    private String mockAssistantAnswer(MemoryStrategy strategy, int historySizeAfterUserMessage) {
        int round = (historySizeAfterUserMessage + 1) / 2;
        return "这是第 " + round + " 轮模拟回答。当前策略是 " + strategy.getTitle()
                + "，重点观察 promptMessages 和 estimatedPromptTokens。";
    }

    private List<ChatMessage> getOrCreateHistory(String sessionId) {
        return sessionHistory.computeIfAbsent(sessionId, key -> Collections.synchronizedList(new ArrayList<>()));
    }

    private List<ChatMessage> copyHistory(String sessionId) {
        List<ChatMessage> history = sessionHistory.get(sessionId);
        if (history == null) {
            return List.of();
        }
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    private List<ChatMessage> tail(List<ChatMessage> messages, int maxSize) {
        if (messages.size() <= maxSize) {
            return new ArrayList<>(messages);
        }
        return new ArrayList<>(messages.subList(messages.size() - maxSize, messages.size()));
    }

    private String shorten(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private ChatMessage systemMessage(String content) {
        return ChatMessage.builder().role("system").content(content).build();
    }

    private ChatMessage userMessage(String content) {
        return ChatMessage.builder().role("user").content(content).build();
    }

    private ChatMessage assistantMessage(String content) {
        return ChatMessage.builder().role("assistant").content(content).build();
    }

    private void requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
    }

    private String asciiDiagram() {
        return """
                第1轮: system + user1
                第2轮: system + user1 + assistant1 + user2
                第3轮: system + user1 + assistant1 + user2 + assistant2 + user3
                   ...

                控制策略:
                ├── full-history          全量历史，成本持续上涨
                ├── message-window        只保留最近 N 条，成本稳定
                └── summary-compression   旧消息摘要 + 最近 N 条，兼顾上下文和成本
                """;
    }
}


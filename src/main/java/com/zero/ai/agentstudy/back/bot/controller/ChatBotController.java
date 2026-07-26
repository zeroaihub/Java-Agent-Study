package com.zero.ai.agentstudy.back.bot.controller;

import com.zero.ai.agentstudy.back.bot.dto.ChatReply;
import com.zero.ai.agentstudy.back.bot.dto.ChatRequest;
import com.zero.ai.agentstudy.back.bot.dto.SummaryResult;
import com.zero.ai.agentstudy.back.bot.dto.TokenUsage;
import com.zero.ai.agentstudy.back.bot.service.ChatBotService;
import com.zero.ai.agentstudy.back.model.ChatMessage;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * AI 聊天机器人统一入口
 *
 * 端点一览:
 *   POST   /bot/chat          普通聊天(多轮 + token统计)
 *   GET    /bot/chat/stream   流式聊天(多轮)
 *   POST   /bot/summarize     对话总结(JSON结构化输出)
 *   GET    /bot/usage         查询token统计
 *   GET    /bot/history       查看历史
 *   DELETE /bot/session       清空会话
 *
 * @author ZeroAi
 */
@Slf4j
@RestController
@RequestMapping("/bot")
public class ChatBotController {

    private final ChatBotService chatBotService;
    private final ExecutorService streamExecutor;

    public ChatBotController(ChatBotService chatBotService,
                             @Qualifier("streamExecutor") ExecutorService streamExecutor) {
        this.chatBotService = chatBotService;
        this.streamExecutor = streamExecutor;
    }

    /**
     * 1. 普通聊天
     * 支持多轮对话(sessionId) + 自定义人设(systemPrompt)
     */
    @PostMapping("/chat")
    public ChatReply chat(@Valid @RequestBody ChatRequest req) {
        return chatBotService.chat(req);
    }

    /**
     * 2. 流式聊天(SSE)
     * 浏览器: new EventSource("/bot/chat/stream?sessionId=xxx&message=讲个故事")
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestParam String message,
                                 @RequestParam(required = false) String sessionId,
                                 @RequestParam(required = false) String systemPrompt) {
        SseEmitter emitter = new SseEmitter(120_000L);

        ChatRequest req = new ChatRequest();
        req.setSessionId(sessionId);
        req.setMessage(message);
        req.setSystemPrompt(systemPrompt);

        // 异步执行流式调用, 不阻塞容器线程(使用统一线程池)
        streamExecutor.submit(() ->
                chatBotService.streamChat(req, emitter));

        return emitter;
    }

    /**
     * 3. 对话总结(JSON结构化输出)
     * 演示知识点8: response_format=json_object
     */
    @PostMapping("/summarize")
    public SummaryResult summarize(@RequestParam String sessionId) {
        return chatBotService.summarize(sessionId);
    }

    /**
     * 4. 查询 token 统计
     */
    @GetMapping("/usage")
    public TokenUsage usage(@RequestParam String sessionId) {
        return chatBotService.getUsage(sessionId);
    }

    /**
     * 5. 查看历史
     */
    @GetMapping("/history")
    public List<ChatMessage> history(@RequestParam String sessionId) {
        return chatBotService.getHistory(sessionId);
    }

    /**
     * 6. 清空会话
     */
    @DeleteMapping("/session")
    public String clearSession(@RequestParam String sessionId) {
        chatBotService.clearSession(sessionId);
        return "会话已清空: " + sessionId;
    }
}

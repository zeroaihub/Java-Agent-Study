package com.zero.ai.agentstudy.day02llmapi.controller;

import com.zero.ai.agentstudy.day02llmapi.common.R;
import com.zero.ai.agentstudy.day02llmapi.dto.ChatRequest;
import com.zero.ai.agentstudy.day02llmapi.dto.ChatResponse;
import com.zero.ai.agentstudy.day02llmapi.service.Day02ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Day02 大模型对话 API。
 * <p>
 * 三大能力：非流式 /chat、流式 /chat/stream（SSE）、多轮会话 /chat/multi。
 * 独立前缀 /api/day02，与 Day01 隔离。
 */
@Slf4j
@RestController
@RequestMapping("/api/day02")
@RequiredArgsConstructor
public class Day02ChatController {

    private final Day02ChatService chatService;

    /** 能力①：非流式对话，一次性返回完整答案与 Token 用量。 */
    @PostMapping("/chat")
    public R<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return R.ok(chatService.chat(request));
    }

    /**
     * 能力②：流式对话，SSE 逐字返回（打字机效果）。
     * <p>produces = text/event-stream，返回 Flux<String>，Spring MVC 自动逐块下发。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@Valid @RequestBody ChatRequest request) {
        return chatService.chatStream(request);
    }

    /** 能力③：多轮会话，需传 conversationId，自动携带历史上下文。 */
    @PostMapping("/chat/multi")
    public R<ChatResponse> chatMulti(@Valid @RequestBody ChatRequest request) {
        return R.ok(chatService.chatMulti(request));
    }
}
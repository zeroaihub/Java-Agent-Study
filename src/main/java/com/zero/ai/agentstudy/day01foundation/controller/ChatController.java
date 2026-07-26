package com.zero.ai.agentstudy.day01foundation.controller;

import com.zero.ai.agentstudy.day01foundation.common.Result;
import com.zero.ai.agentstudy.day01foundation.dto.ChatRequest;
import com.zero.ai.agentstudy.day01foundation.dto.ChatResponse;
import com.zero.ai.agentstudy.day01foundation.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Day1 第一个 AI Demo 接口
 * <p>
 * 测试：
 * curl -X POST http://localhost:8080/api/day01/chat \
 *      -H "Content-Type: application/json" \
 *      -d '{"message":"你好，请介绍一下你自己"}'
 */
@RestController
@RequestMapping("/api/day01/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 聊天接口。
     *
     * @param request 聊天请求（@Valid 触发参数校验，message 为空返回 400）
     * @return 统一包装的聊天响应
     */
    @PostMapping
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return Result.success(chatService.chat(request));
    }
}
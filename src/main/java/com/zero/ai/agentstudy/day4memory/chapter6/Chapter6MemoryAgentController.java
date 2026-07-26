package com.zero.ai.agentstudy.day4memory.chapter6;

import com.zero.ai.agentstudy.back.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Day4 第六章：Java 实现 Memory Agent。
 */
@RestController
@RequestMapping("/api/day4/chapter6")
@RequiredArgsConstructor
public class Chapter6MemoryAgentController {

    private final Chapter6MemoryAgentService memoryAgentService;

    @PostMapping("/chat")
    public Result<MemoryChatResponse> chat(@RequestBody MemoryChatRequest request) {
        return Result.success(memoryAgentService.chat(request));
    }

    @GetMapping("/history")
    public Result<MemoryChatResponse> history(@RequestParam String userId,
                                              @RequestParam String sessionId) {
        return Result.success(memoryAgentService.history(userId, sessionId));
    }

    @DeleteMapping("/clear")
    public Result<String> clear(@RequestParam String userId,
                                @RequestParam String sessionId) {
        memoryAgentService.clear(userId, sessionId);
        return Result.success("会话已清空: " + userId + ":" + sessionId);
    }
}


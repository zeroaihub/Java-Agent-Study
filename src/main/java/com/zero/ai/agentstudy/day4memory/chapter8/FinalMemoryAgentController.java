package com.zero.ai.agentstudy.day4memory.chapter8;

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
 * Day4 第八章：完成 Memory Agent。
 */
@RestController
@RequestMapping("/api/day4/chapter8")
@RequiredArgsConstructor
public class FinalMemoryAgentController {

    private final FinalMemoryAgentService memoryAgentService;

    @PostMapping("/chat")
    public Result<FinalMemoryChatResponse> chat(@RequestBody FinalMemoryChatRequest request) {
        return Result.success(memoryAgentService.chat(request));
    }

    @GetMapping("/inspect")
    public Result<FinalMemoryChatResponse> inspect(@RequestParam String userId,
                                                   @RequestParam String sessionId) {
        return Result.success(memoryAgentService.inspect(userId, sessionId));
    }

    @DeleteMapping("/clear-session")
    public Result<String> clearSession(@RequestParam String userId,
                                       @RequestParam String sessionId) {
        memoryAgentService.clearSession(userId, sessionId);
        return Result.success("会话已清空: " + userId + ":" + sessionId);
    }
}


package com.zero.ai.agentstudy.day4memory.chapter5;

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
 * Day4 第五章：Spring AI 与 LangChain4j 中的 Memory。
 */
@RestController
@RequestMapping("/api/day4/chapter5")
@RequiredArgsConstructor
public class Chapter5FrameworkMemoryController {

    private final Chapter5FrameworkMemoryService memoryService;

    @GetMapping("/design")
    public Result<FrameworkDesignResponse> design() {
        return Result.success(memoryService.design());
    }

    @PostMapping("/chat")
    public Result<SessionMemoryResponse> chat(@RequestBody SessionRequest request) {
        return Result.success(memoryService.chat(request));
    }

    @GetMapping("/history")
    public Result<SessionMemoryResponse> history(@RequestParam String userId,
                                                 @RequestParam String sessionId) {
        return Result.success(memoryService.history(userId, sessionId));
    }

    @DeleteMapping("/clear")
    public Result<String> clear(@RequestParam String userId,
                                @RequestParam String sessionId) {
        memoryService.clear(userId, sessionId);
        return Result.success("会话已清空: " + userId + ":" + sessionId);
    }
}


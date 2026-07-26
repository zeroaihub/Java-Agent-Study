package com.zero.ai.agentstudy.day4memory.chapter3;

import com.zero.ai.agentstudy.back.common.Result;
import com.zero.ai.agentstudy.back.model.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Day4 第三章：Chat Memory 工作原理。
 *
 * 测试接口：
 * - GET  /api/day4/chapter3/growth-report
 * - GET  /api/day4/chapter3/strategies
 * - POST /api/day4/chapter3/full-history
 * - POST /api/day4/chapter3/message-window
 * - POST /api/day4/chapter3/summary-compression
 * - GET  /api/day4/chapter3/history?sessionId=s1
 * - DELETE /api/day4/chapter3/clear?sessionId=s1
 */
@RestController
@RequestMapping("/api/day4/chapter3")
@RequiredArgsConstructor
public class Chapter3MemoryController {

    private final Chapter3MemoryService memoryService;

    @GetMapping("/growth-report")
    public Result<MemoryGrowthReport> growthReport() {
        return Result.success(memoryService.growthReport());
    }

    @GetMapping("/strategies")
    public Result<List<MemoryStrategyView>> strategies() {
        return Result.success(memoryService.strategies());
    }

    @PostMapping("/full-history")
    public Result<Chapter3ChatResponse> fullHistory(@RequestBody Chapter3ChatRequest request) {
        return Result.success(memoryService.fullHistory(request.sessionId(), request.message()));
    }

    @PostMapping("/message-window")
    public Result<Chapter3ChatResponse> messageWindow(@RequestBody Chapter3ChatRequest request) {
        return Result.success(memoryService.messageWindow(request.sessionId(), request.message()));
    }

    @PostMapping("/summary-compression")
    public Result<Chapter3ChatResponse> summaryCompression(@RequestBody Chapter3ChatRequest request) {
        return Result.success(memoryService.summaryCompression(request.sessionId(), request.message()));
    }

    @GetMapping("/history")
    public Result<List<ChatMessage>> history(@RequestParam String sessionId) {
        return Result.success(memoryService.history(sessionId));
    }

    @DeleteMapping("/clear")
    public Result<String> clear(@RequestParam String sessionId) {
        memoryService.clear(sessionId);
        return Result.success("会话已清空: " + sessionId);
    }
}


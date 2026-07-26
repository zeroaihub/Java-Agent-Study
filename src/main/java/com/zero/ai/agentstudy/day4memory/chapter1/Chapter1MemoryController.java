package com.zero.ai.agentstudy.day4memory.chapter1;

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
 * Day4 第一章：为什么 Agent 必须有 Memory。
 *
 * 测试路径：
 * 1. 无记忆：
 *    POST /api/day4/chapter1/no-memory
 *    {"message":"我叫张三，是 Java 工程师"}
 *
 *    POST /api/day4/chapter1/no-memory
 *    {"message":"我叫什么名字？"}
 *
 * 2. 有记忆：
 *    POST /api/day4/chapter1/with-memory
 *    {"sessionId":"s1","message":"我叫张三，是 Java 工程师"}
 *
 *    POST /api/day4/chapter1/with-memory
 *    {"sessionId":"s1","message":"我叫什么名字？我的职业是什么？"}
 *
 * 3. Session 隔离：
 *    使用 sessionId=s2 再问“我叫什么名字”，应该不会读到 s1 的记忆。
 */
@RestController
@RequestMapping("/api/day4/chapter1")
@RequiredArgsConstructor
public class Chapter1MemoryController {

    private final Chapter1MemoryService memoryService;

    @PostMapping("/no-memory")
    public Result<Chapter1ChatResponse> chatWithoutMemory(@RequestBody Chapter1ChatRequest request) {
        return Result.success(memoryService.chatWithoutMemory(request.message()));
    }

    @PostMapping("/with-memory")
    public Result<Chapter1ChatResponse> chatWithMemory(@RequestBody Chapter1ChatRequest request) {
        return Result.success(memoryService.chatWithMemory(request.sessionId(), request.message()));
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


package com.zero.ai.agentstudy.day4memory.chapter2;

import com.zero.ai.agentstudy.back.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Day4 第二章：Memory 分类。
 *
 * 测试接口：
 * - GET  /api/day4/chapter2/map
 * - GET  /api/day4/chapter2/categories
 * - GET  /api/day4/chapter2/examples
 * - POST /api/day4/chapter2/classify
 */
@RestController
@RequestMapping("/api/day4/chapter2")
@RequiredArgsConstructor
public class Chapter2MemoryController {

    private final Chapter2MemoryService memoryService;

    @GetMapping("/map")
    public Result<MemoryMapResponse> memoryMap() {
        return Result.success(memoryService.memoryMap());
    }

    @GetMapping("/categories")
    public Result<List<MemoryCategoryView>> categories() {
        return Result.success(memoryService.categories());
    }

    @GetMapping("/examples")
    public Result<List<MemoryClassifyResponse>> examples() {
        return Result.success(memoryService.examples());
    }

    @PostMapping("/classify")
    public Result<MemoryClassifyResponse> classify(@RequestBody MemoryClassifyRequest request) {
        return Result.success(memoryService.classify(request.content()));
    }
}


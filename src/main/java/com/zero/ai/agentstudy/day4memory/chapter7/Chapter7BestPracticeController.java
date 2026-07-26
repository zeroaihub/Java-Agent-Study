package com.zero.ai.agentstudy.day4memory.chapter7;

import com.zero.ai.agentstudy.back.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Day4 第七章：企业最佳实践。
 */
@RestController
@RequestMapping("/api/day4/chapter7")
@RequiredArgsConstructor
public class Chapter7BestPracticeController {

    private final Chapter7BestPracticeService bestPracticeService;

    @GetMapping("/overview")
    public Result<BestPracticeResponse> overview() {
        return Result.success(bestPracticeService.overview());
    }

    @GetMapping("/lifecycle")
    public Result<List<MemoryLifecyclePolicy>> lifecycle() {
        return Result.success(bestPracticeService.lifecyclePolicies());
    }

    @PostMapping("/compression-decision")
    public Result<CompressionDecision> compressionDecision(@RequestBody CompressionRequest request) {
        return Result.success(bestPracticeService.compressionDecision(request));
    }

    @PostMapping("/profile-update-decision")
    public Result<ProfileUpdateDecision> profileUpdateDecision(@RequestBody ProfileUpdateRequest request) {
        return Result.success(bestPracticeService.profileUpdateDecision(request));
    }

    @GetMapping("/rag-coordination")
    public Result<RagCoordinationResponse> ragCoordination() {
        return Result.success(bestPracticeService.ragCoordination());
    }
}


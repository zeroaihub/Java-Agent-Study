package com.zero.ai.agentstudy.day4memory.chapter4;

import com.zero.ai.agentstudy.back.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Day4 第四章：长期 Memory 设计。
 */
@RestController
@RequestMapping("/api/day4/chapter4")
@RequiredArgsConstructor
public class Chapter4ProfileController {

    private final Chapter4ProfileService profileService;

    @GetMapping("/schema")
    public Result<ProfileSchemaResponse> schema() {
        return Result.success(profileService.schema());
    }

    @PostMapping("/profile")
    public Result<UserProfile> upsert(@RequestBody UserProfilePatch patch) {
        return Result.success(profileService.upsert(patch));
    }

    @GetMapping("/profile")
    public Result<UserProfile> get(@RequestParam String userId) {
        return Result.success(profileService.get(userId));
    }

    @PostMapping("/decide")
    public Result<FieldDecisionResponse> decide(@RequestBody FieldDecisionRequest request) {
        return Result.success(profileService.decide(request.content()));
    }
}


package com.zero.ai.agentstudy.back.demo;

import com.zero.ai.agentstudy.back.model.ChatCompletionRequest;
import com.zero.ai.agentstudy.back.model.ChatCompletionResponse;
import com.zero.ai.agentstudy.back.model.ChatMessage;
import com.zero.ai.agentstudy.back.service.AiService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Demo2: Chat Completions 接口参数详解
 *
 * 学习目标:
 *   1. 掌握 temperature / max_tokens / top_p 等核心参数
 *   2. 理解参数对输出结果的影响
 *   3. 学会构造完整的 ChatCompletionRequest
 *
 * 测试: POST http://localhost:8080/demo2/creative  (高温度, 创意)
 *      POST http://localhost:8080/demo2/precise    (低温度, 精确)
 *      POST http://localhost:8080/demo2/limited    (限制长度)
 *
 * @author ZeroAi
 */
@Slf4j
@RestController
@RequestMapping("/demo2")
@RequiredArgsConstructor
public class Demo2ChatCompletions {

    private final AiService aiService;

    /**
     * 高温度(1.5): 创意发散
     * 同一个问题, 每次回答都不一样, 而且更"天马行空"
     */
    @PostMapping("/creative")
    public String creative(@RequestBody Question req) {
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage("用一句话描述'秋天',要够有想象力")))
                .temperature(1.5)   // 高温度: 创意
                .build();
        String content = aiService.chat(request).getChoices().get(0).getMessage().getContent();

        log.info(content);
        return content;
    }

    /**
     * 低温度(0): 精确严谨
     * 适合: 代码生成、数学计算、事实问答
     */
    @PostMapping("/precise")
    public String precise(@RequestBody Question req) {
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage("Java中HashMap的底层数据结构是什么?")))
                .temperature(0.0)   // 低温度: 严谨
                .build();
        String content = aiService.chat(request).getChoices().get(0).getMessage().getContent();

        log.info(content);
        return content;    }

    /**
     * 限制最大token: 控制输出长度(也控制成本!)
     * 如果输出被截断, finish_reason 会变成 "length" 而不是 "stop"
     */
    @PostMapping("/limited")
    public ChatCompletionResponse limited(@RequestBody Question req) {
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage("详细介绍一下Spring Boot")))
                .temperature(0.7)
                .maxTokens(50)      // 只允许生成50个token, 会被截断!
                .build();
        ChatCompletionResponse resp = aiService.chat(request);

        // 重点观察 finish_reason:
        //   "stop"   = 正常结束
        //   "length" = 因为达到 max_tokens 被强制截断
        log.info("finish_reason={}, usage={}",
                resp.getChoices().get(0).getFinishReason(), resp.getUsage());
        return resp;
    }

    private ChatMessage userMessage(String content) {
        return ChatMessage.builder().role("user").content(content).build();
    }

    @Data
    public static class Question {
        private String text;
    }

    @PostMapping("/compare")
    public String compare(@RequestBody Question req) {
        ChatCompletionRequest build = ChatCompletionRequest.builder()
                .temperature(0.0)
                .messages(List.of(userMessage(req.getText())))
                .build();

        ChatCompletionResponse chat = aiService.chat(build);

        log.info(chat.getChoices().get(0).getMessage().getContent());
        return chat.getChoices().get(0).getMessage().getContent();
    }
    @PostMapping("/compare7")
    public String compare7(@RequestBody Question req) {
        ChatCompletionRequest build = ChatCompletionRequest.builder()
                .temperature(0.7)
                .messages(List.of(userMessage(req.getText())))
                .build();

        ChatCompletionResponse chat = aiService.chat(build);

        log.info(chat.getChoices().get(0).getMessage().getContent());
        return chat.getChoices().get(0).getMessage().getContent();
    }

    @PostMapping("/compare15")
    public String compare15(@RequestBody Question req) {
        ChatCompletionRequest build = ChatCompletionRequest.builder()
                .temperature(1.5)
                .messages(List.of(userMessage(req.getText())))
                .build();

        ChatCompletionResponse chat = aiService.chat(build);

        log.info(chat.getChoices().get(0).getMessage().getContent());
        return chat.getChoices().get(0).getMessage().getContent();
    }
}

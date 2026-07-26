package com.zero.ai.agentstudy.back.demo;

import com.zero.ai.agentstudy.back.model.ChatCompletionRequest;
import com.zero.ai.agentstudy.back.model.ChatCompletionResponse;
import com.zero.ai.agentstudy.back.model.ChatMessage;
import com.zero.ai.agentstudy.back.service.AiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

/**
 * Demo1: 大模型 API 调用初体验
 *
 * 学习目标:
 *   1. 理解"调大模型"本质就是发 HTTP 请求
 *   2. 看清"输入->输出"的完整流程
 *   3. 认识 ChatMessage(role=user) 这种最基本的请求结构
 *
 * 测试: POST http://localhost:8080/demo1/chat
 *      Body: {"question":"你好,请用一句话介绍你自己"}
 *
 * @author ZeroAi
 */
@Slf4j
@RestController
@RequestMapping("/demo1")
@RequiredArgsConstructor
public class Demo1ApiPrinciple {

    /** 注入大模型服务(所有 Demo 都复用它) */
    private final AiService aiService;
    /**
     * 最简对话: 一问一答
     * 这里手动构造消息列表, 让你看清每一步
     */
    @PostMapping("/chat")
    public String chat(@RequestBody ChatRequest req) {
        // ① 构造一条 user 消息
        ChatMessage userMessage = ChatMessage.builder()
                .role("user")
                .content(req.getQuestion())
                .build();

        // ② 放进 messages 数组(知识点3会详解为什么是数组)
        List<ChatMessage> messages = List.of(userMessage);

        // ③ 调用大模型 —— 这一步内部就是在发 HTTP POST 请求!
        //    等价于: curl -X POST https://api.deepseek.com/v1/chat/completions \
        //            -H "Authorization: Bearer sk-xxx" -d '{...}'
        String answer = aiService.chat(messages);

        log.info("提问: {}", req.getQuestion());
        log.info("回答: {}", answer);
        return answer;
    }


    /**
     * 拿到完整响应(含 token 统计)
     * 这个接口让你看到"响应里到底有什么"
     */
    @PostMapping("/chat/raw")
    public ChatCompletionResponse chatRaw(@RequestBody ChatRequest req) {
        ChatMessage userMessage = ChatMessage.builder()
                .role("user")
                .content(req.getQuestion())
                .build();

        // 直接返回完整响应对象, 你能看到 id / choices / usage 等字段
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage))
                .build();
        return aiService.chat(request);
    }

    /** Demo1 请求体 */
    @lombok.Data
    public static class ChatRequest {
        private String question;

        private String model;
    }


    /**
     * 最简对话: 一问一答
     * 这里手动构造消息列表, 让你看清每一步
     */
    @PostMapping("/chat-with-model")
    public String chatWithModel(@RequestBody ChatRequest req) {
        // ① 构造一条 user 消息
        ChatMessage userMessage = ChatMessage.builder()
                .role("user")
                .content(req.getQuestion())
                .build();

        // ② 放进 messages 数组(知识点3会详解为什么是数组)
        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
                .model(req.getModel())
                .messages(Collections.singletonList(userMessage))
                .build();

        ChatCompletionResponse chatRes = aiService.chat(chatCompletionRequest);

        log.info("提问: {}", req.getQuestion());
        log.info("回答: {}", chatRes.getChoices().get(0).getMessage().getContent());
        return  chatRes.getChoices().get(0).getMessage().getContent();
    }

}

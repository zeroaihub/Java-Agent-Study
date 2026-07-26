package com.zero.ai.agentstudy.back.demo;

import com.zero.ai.agentstudy.back.model.ChatMessage;
import com.zero.ai.agentstudy.back.service.AiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Demo3: Message 为什么是数组
 *
 * 学习目标:
 *   1. 理解"对话历史"是通过 messages 数组传递的
 *   2. 认识 system / user / assistant 三种角色
 *   3. 亲手模拟一次"多轮对话"(即使模型无状态, 也能记住!)
 *
 * 测试: POST http://localhost:8080/demo3/memory
 *
 * 重点: 这个 Demo 故意不用"会话存储",
 *       就是为了让你看清"历史消息要手动拼接"这个事实。
 *
 * @author ZeroAi
 */
@Slf4j
@RestController
@RequestMapping("/demo3")
@RequiredArgsConstructor
public class Demo3MessageArray {

    private final AiService aiService;

    /**
     * 演示: 手动拼接历史消息, 让模型"记住"信息
     *
     * 流程:
     *   1. 第一句: 用户告诉模型"我叫张三"
     *   2. 模型回复(我们硬编码, 模拟)
     *   3. 第二句: 用户问"我叫什么"
     *   4. 把1+2+3三条消息一起发给模型, 它能答出"张三"
     *
     * 这就是多轮对话的本质!
     */
    @PostMapping("/memory")
    public String memory() {
        List<ChatMessage> messages = new ArrayList<>();

        // ① 历史: 用户说过的话
        messages.add(ChatMessage.builder()
                .role("user").content("我叫张三,今年28岁").build());

        // ② 历史: AI 之前的回复(必须带上, 否则模型不知道自己答过什么)
        //    这里用一段模拟的回复, 实际项目从存储里取
        messages.add(ChatMessage.builder()
                .role("assistant").content("你好张三!很高兴认识你。").build());

        // ③ 本次: 新问题
        messages.add(ChatMessage.builder()
                .role("user").content("我叫什么名字?今年多大?").build());

        // ④ 把完整历史一起发 —— 这就是"数组"的意义!
        log.info("发送的消息数: {}", messages.size());
        String answer = aiService.chat(messages);
        log.info("模型回答: {}", answer);
        return answer;
    }

    /**
     * 反例: 只发新问题, 不带历史 —— 模型就"失忆"了
     */
    @PostMapping("/forget")
    public String forget() {
        // 只发当前问题, 模型不知道之前聊过什么
        List<ChatMessage> messages = List.of(
                ChatMessage.builder().role("user").content("我叫什么名字?").build()
        );
        return aiService.chat(messages);
    }

    /**
     * 演示角色顺序:
     *   标准顺序是 system -> user -> assistant -> user -> assistant -> ...
     *   system 必须在最前面, 且只出现一次
     */
    @PostMapping("/roles")
    public String roles() {
        return aiService.chat(List.of(
                // system: 设定AI身份(知识点4详解)
                ChatMessage.builder().role("system")
                        .content("你是一个只用文言文回答的AI").build(),
                // user: 用户提问
                ChatMessage.builder().role("user")
                        .content("今天天气怎么样").build()
        ));
    }


    /**
     * 演示: 手动拼接历史消息, 让模型"记住"信息
     *
     * 流程:
     *   1. 第一句: 用户告诉模型"我叫张三"
     *   2. 模型回复(我们硬编码, 模拟)
     *   3. 第二句: 用户问"我叫什么"
     *   4. 把1+2+3三条消息一起发给模型, 它能答出"张三"
     *
     * 这就是多轮对话的本质!
     */
    @PostMapping("/memory2")
    public String memory2() {
        List<ChatMessage> messages = new ArrayList<>();
        //我手动新增的消息
        messages.add(ChatMessage.builder()
                .role("system").content("你是一个记忆力好的AI，但是偶尔会忘记一些事情").build());

        // ① 历史: 用户说过的话
        messages.add(ChatMessage.builder()
                .role("user").content("我叫张三,今年28岁").build());

        // ② 历史: AI 之前的回复(必须带上, 否则模型不知道自己答过什么)
        //    这里用一段模拟的回复, 实际项目从存储里取
        messages.add(ChatMessage.builder()
                .role("assistant").content("你好张三!很高兴认识你。").build());

        // ③ 本次: 新问题
        messages.add(ChatMessage.builder()
                .role("user").content("我叫什么名字?今年多大?").build());

        // ④ 把完整历史一起发 —— 这就是"数组"的意义!
        log.info("发送的消息数: {}", messages.size());
        String answer = aiService.chat(messages);
        log.info("模型回答: {}", answer);
        return answer;
    }
}

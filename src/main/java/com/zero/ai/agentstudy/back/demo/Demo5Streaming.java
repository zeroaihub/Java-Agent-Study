package com.zero.ai.agentstudy.back.demo;

import com.zero.ai.agentstudy.back.model.ChatCompletionChunk;
import com.zero.ai.agentstudy.back.model.ChatMessage;
import com.zero.ai.agentstudy.back.service.AiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demo5: 流式输出实现
 *
 * 学习目标:
 *   1. 理解 SSE(Server-Sent Events) 流式协议
 *   2. 掌握"大模型流式响应 → 转发给前端"的完整链路
 *   3. 认识 delta 是"增量内容", 需要客户端拼接
 *
 * 测试:
 *   浏览器访问: http://localhost:8080/demo5/stream?question=讲个笑话
 *   或 curl: curl -N "http://localhost:8080/demo5/stream?question=讲个笑话"
 *
 * 重点观察: 文字是一个字一个字"蹦"出来的, 不是一次性返回!
 *
 * @author ZeroAi
 */
@Slf4j
@RestController
@RequestMapping("/demo5")
public class Demo5Streaming {

    private final AiService aiService;

    /** 注入统一线程池(规范: 禁止每次请求 new 线程池) */
    private final ExecutorService streamExecutor;

    public Demo5Streaming(AiService aiService,
                          @Qualifier("streamExecutor") ExecutorService streamExecutor) {
        this.aiService = aiService;
        this.streamExecutor = streamExecutor;
    }

    /**
     * 流式对话接口
     *
     * 原理链路:
     *   ① 浏览器请求这个接口(produces=text/event-stream)
     *   ② Spring 创建 SseEmitter, 保持连接不断开
     *   ③ 异步线程调用大模型 streamChat, 拿到 Flux<Chunk>
     *   ④ 每来一个 chunk, 提取 delta.content, 通过 emitter.send() 推给浏览器
     *   ⑤ 大模型流结束, emitter.complete() 关闭连接
     *
     * 浏览器端用 EventSource 接收:
     *   const es = new EventSource("/demo5/stream?question=hi");
     *   es.onmessage = e => console.log(e.data);  // 每次收到一个字
     */
    @GetMapping(value = "/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String question) {
        // 超时设长一点, 流式生成可能需要时间
        SseEmitter emitter = new SseEmitter(120_000L);

        // 异步执行, 不阻塞 Tomcat 线程(使用统一线程池, 避免泄漏)
        streamExecutor.submit(() -> {
            try {
                List<ChatMessage> messages = List.of(
                        ChatMessage.builder().role("user").content(question).build()
                );

                // 调用流式接口, block() 收集每个 chunk
                // 注意: 真实项目用 subscribe 异步, 这里为演示用 blockLast 同步等待
                StringBuilder fullResponse = new StringBuilder();

                aiService.streamChat(messages)
                        .doOnNext(chunk -> {
                            // 每个 chunk 包含增量内容 delta
                            String delta = extractDelta(chunk);
                            log.info("delta=" + delta);
                            if (delta != null && !delta.isEmpty()) {
                                fullResponse.append(delta);
                                try {
                                    // 推送给前端(浏览器收到一个 data 事件)
                                    emitter.send(SseEmitter.event().data(delta));
                                } catch (IOException e) {
                                    log.error("推送失败", e);
                                }
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                emitter.complete();
                                log.info("流式完成, 完整回复: {}", fullResponse);
                            } catch (IOException e) {
                                log.error("关闭失败", e);
                            }
                        })
                        .blockLast();   // 阻塞直到流结束

            } catch (Exception e) {
                log.error("流式调用异常", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 从 chunk 中提取增量内容
     * chunk.choices[0].delta.content 就是本次新增的文本。
     *
     * 注意: 思考型模型(如 Qwen-reasoning / DeepSeek-R1)在正式回答前会先输出
     * 思考过程, 此时 content 为 null, 内容在 reasoning_content 字段。
     * 这里做兼容: content 为空时回退取 reasoningContent, 否则会一直拿到 null。
     */
    private String extractDelta(ChatCompletionChunk chunk) {
        if (chunk.getChoices() == null || chunk.getChoices().isEmpty()) {
            return null;
        }
        ChatMessage delta = chunk.getChoices().get(0).getDelta();
        if (delta == null) {
            return null;
        }
        // 优先取正式回答内容; 思考阶段 content 为空, 回退取思考内容
        if (delta.getContent() != null && !delta.getContent().isEmpty()) {
            return delta.getContent();
        }
        return delta.getReasoningContent();
    }


    @GetMapping(value = "/stream-with-count", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream_with_count(@RequestParam String question) {
        // 超时设长一点, 流式生成可能需要时间
        SseEmitter emitter = new SseEmitter(120_000L);
        AtomicInteger time = new AtomicInteger();
        // 异步执行, 不阻塞 Tomcat 线程(使用统一线程池, 避免泄漏)
        streamExecutor.submit(() -> {
            try {
                List<ChatMessage> messages = List.of(
                        ChatMessage.builder().role("user").content(question).build()
                );

                // 调用流式接口, block() 收集每个 chunk
                // 注意: 真实项目用 subscribe 异步, 这里为演示用 blockLast 同步等待
                StringBuilder fullResponse = new StringBuilder();

                aiService.streamChat(messages)
                        .doOnNext(chunk -> {
                            time.getAndIncrement();
                            // 每个 chunk 包含增量内容 delta
                            String delta = extractDelta(chunk);
                            log.info("delta=" + delta);
                            if (delta != null && !delta.isEmpty()) {
                                fullResponse.append(delta);
                                try {
                                    // 推送给前端(浏览器收到一个 data 事件)
                                    emitter.send(SseEmitter.event().data(delta));
                                } catch (IOException e) {
                                    log.error("推送失败", e);
                                }
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                emitter.complete();
                                log.info("流式完成, 完整回复: {}", fullResponse);
                            } catch (IOException e) {
                                log.error("关闭失败", e);
                            }
                        })
                        .blockLast();   // 阻塞直到流结束
                log.info("一共执行了多少次：" + time.get());
            } catch (Exception e) {
                log.error("流式调用异常", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

}

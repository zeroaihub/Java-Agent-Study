package com.zero.ai.agentstudy.back.demo;

import com.zero.ai.agentstudy.back.model.ChatMessage;
import com.zero.ai.agentstudy.back.service.AiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/**
 * Demo6: 多轮对话实现
 *
 * 学习目标:
 *   1. 用会话ID(sessionId)隔离不同用户的对话
 *   2. 用内存存储历史消息(生产环境用 Redis)
 *   3. 实现"滑动窗口"截断策略, 防止上下文超限
 *   4. 每轮: 把历史+新问题发给模型, 再把回复存回去
 *
 * 测试流程(注意要用同一个 sessionId):
 *   POST /demo6/chat?sessionId=s1  body: "我叫张三"
 *   POST /demo6/chat?sessionId=s1  body: "我叫什么名字"   ← 模型能答出"张三"!
 *   POST /demo6/history?sessionId=s1   ← 查看完整历史
 *   POST /demo6/clear?sessionId=s1     ← 清空会话
 *
 * @author ZeroAi
 */
@Slf4j
@RestController
@RequestMapping("/demo6")
@RequiredArgsConstructor
public class Demo6MultiTurn {

    private final AiService aiService;

    /**
     * 会话存储: sessionId -> 消息历史
     * 用 LinkedHashMap(按插入顺序) + synchronizedMap 包装, 既能保证线程安全,
     * 又能通过插入顺序找出"最旧的" sessionId
     * 生产环境应替换为 Redis: redisTemplate.opsForList().range(sessionId, 0, -1)
     */
    private final Map<String, List<ChatMessage>> sessionStore =
            Collections.synchronizedMap(new LinkedHashMap<>());

    /** 保留最近几轮(1轮=user+assistant=2条消息), 这里保留最近5轮=10条 */
    private static final int MAX_MESSAGES = 10;


    private static final int MAX_CHAT_NUM = 100;

    /**
     * 多轮对话主接口
     */
    @PostMapping("/chat")
    public String chat(@RequestParam String sessionId,
                       @RequestBody String userInput) {
        if (sessionStore.size() > MAX_CHAT_NUM) {
            log.info("超出{}个会话, 移除最旧的会话", MAX_CHAT_NUM);
            removeOldestSession();
        }
        // ① 取出(或新建)该会话的历史
        List<ChatMessage> history = sessionStore.computeIfAbsent(sessionId,
                k -> new ArrayList<>());

        // ② 把用户本次输入追加到历史
        ChatMessage userMsg = ChatMessage.builder()
                .role("user").content(userInput).build();
        history.add(userMsg);

        // ③ 截断: 只取最近 MAX_MESSAGES 条, 防止上下文爆炸
        //    注意: system 消息(如果有)应始终保留, 这里简化处理
        List<ChatMessage> messagesToSend = truncate(history, MAX_MESSAGES);

        // ④ 调用大模型
        log.info("会话[{}] 发送{}条消息", sessionId, messagesToSend.size());
        String reply = aiService.chat(messagesToSend);

        // ⑤ 把 AI 回复存回历史(关键!下次对话模型才知道自己说过什么)
        ChatMessage assistantMsg = ChatMessage.builder()
                .role("assistant").content(reply).build();
        history.add(assistantMsg);

        return reply;
    }

    /**
     * 查看某个会话的完整历史
     */
    @GetMapping("/history")
    public List<ChatMessage> history(@RequestParam String sessionId) {
        return sessionStore.getOrDefault(sessionId, List.of());
    }

    /**
     * 清空会话
     */
    @DeleteMapping("/clear")
    public String clear(@RequestParam String sessionId) {
        sessionStore.remove(sessionId);
        return "会话已清空: " + sessionId;
    }

    /**
     * 删除最旧的会话(最早插入的 sessionId)
     *
     * LinkedHashMap 按插入顺序迭代, 第一个元素即最旧的会话。
     * 由于 sessionStore 是 synchronizedMap 包装的, 迭代时必须在同一把锁上手动同步,
     * 否则可能抛出 ConcurrentModificationException。
     */
    private void removeOldestSession() {
        synchronized (sessionStore) {
            Iterator<String> it = sessionStore.keySet().iterator();
            if (it.hasNext()) {
                String oldestSessionId = it.next();
                it.remove();
                log.info("已移除最旧的会话: {}", oldestSessionId);
            }
        }
    }

    /**
     * 滑动窗口截断策略
     * 保留最近 n 条消息, 避免上下文超限
     *
     * 生产环境更精细的做法:
     *   - 用 tokenizer 计算精确 token 数
     *   - 始终保留 system 消息
     *   - 对被截断的内容做摘要
     */
    private List<ChatMessage> truncate(List<ChatMessage> history, int maxSize) {
        if (history.size() <= maxSize) {
            return new ArrayList<>(history);
        }
        // 只取最后 maxSize 条
        return new ArrayList<>(history.subList(history.size() - maxSize, history.size()));
    }
}

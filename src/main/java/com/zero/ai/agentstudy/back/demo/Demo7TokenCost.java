package com.zero.ai.agentstudy.back.demo;

import com.zero.ai.agentstudy.back.model.ChatCompletionRequest;
import com.zero.ai.agentstudy.back.model.ChatCompletionResponse;
import com.zero.ai.agentstudy.back.model.ChatMessage;
import com.zero.ai.agentstudy.back.service.AiService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Demo7: Token 统计与成本计算
 *
 * 学习目标:
 *   1. 从响应中读取 usage 字段(prompt/completion/total tokens)
 *   2. 模拟计算费用(按 token 计费!)
 *   3. 直观感受"多轮对话为什么贵"
 *
 * 测试:
 *   POST /demo7/cost   body: "详细解释一下什么是闭包"
 *
 * 重点: 比较不同长度问题的 token 消耗, 理解"长问题=贵"
 *
 * @author ZeroAi
 */
@Slf4j
@RestController
@RequestMapping("/demo7")
@RequiredArgsConstructor
public class Demo7TokenCost {

    private final AiService aiService;

    /** 模拟定价(元/千token), 实际以服务商为准 */
    private static final double INPUT_PRICE_PER_1K = 0.001;
    private static final double OUTPUT_PRICE_PER_1K = 0.002;

    /**
     * 调用并返回带成本统计的结果
     */
    @PostMapping("/cost")
    public CostResult cost(@RequestBody Question req) {
        List<ChatMessage> messages = List.of(
                ChatMessage.builder().role("user").content(req.getText()).build()
        );

        // 调用(注意这里用 chat(request) 拿完整响应, 才有 usage 字段)
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(messages)
                .build();
        ChatCompletionResponse resp = aiService.chat(request);

        // ① 提取 token 统计
        ChatCompletionResponse.Usage usage = resp.getUsage();
        int inputTokens = usage.getPromptTokens();
        int outputTokens = usage.getCompletionTokens();
        int totalTokens = usage.getTotalTokens();

        // ② 计算费用
        double cost = inputTokens * INPUT_PRICE_PER_1K / 1000
                + outputTokens * OUTPUT_PRICE_PER_1K / 1000;

        log.info("输入token={}, 输出token={}, 总token={}, 费用={}元",
                inputTokens, outputTokens, totalTokens, cost);

        return new CostResult(
                resp.getChoices().get(0).getMessage().getContent(),
                inputTokens,
                outputTokens,
                totalTokens,
                cost
        );
    }

    /**
     * 多轮对话的成本累积演示
     * 模拟3轮对话, 每轮带历史, 观察 token 增长
     */
    @PostMapping("/accumulate")
    public String accumulate() {
        StringBuilder sb = new StringBuilder();
        List<ChatMessage> history = new ArrayList<>();
        double totalCost = 0;

        String[] questions = {
                "什么是Java?",
                "它和Python有什么区别?",   // 这轮会带上第1轮的历史
                "请总结一下前两轮的对话"    // 这轮会带上前两轮的历史
        };

        // 记录每轮的输入/输出 token, 用于预测未来
        List<Integer> inputHistory = new ArrayList<>();
        List<Integer> outputHistory = new ArrayList<>();

        for (int i = 0; i < questions.length; i++) {
            history.add(ChatMessage.builder().role("user").content(questions[i]).build());

            ChatCompletionRequest request = ChatCompletionRequest.builder()
                    .messages(new ArrayList<>(history))
                    .build();
            ChatCompletionResponse resp = aiService.chat(request);
            String reply = resp.getChoices().get(0).getMessage().getContent();

            // 存回历史
            history.add(ChatMessage.builder().role("assistant").content(reply).build());

            // 统计这轮的 token
            int input = resp.getUsage().getPromptTokens();
            int output = resp.getUsage().getCompletionTokens();
            double cost = input * INPUT_PRICE_PER_1K / 1000 + output * OUTPUT_PRICE_PER_1K / 1000;
            totalCost += cost;

            inputHistory.add(input);
            outputHistory.add(output);

            // ★ 每轮结束后, 预测"若继续聊下去, 未来10轮的总费用"
            double forecast = forecastNext10Cost(inputHistory, outputHistory);

            sb.append(String.format("第%d轮: 输入%d, 输出%d, 费用%.6f元, 如果继续聊下去, 预估未来10轮总费用约 %.6f元\n",
                    i + 1, input, output, cost, forecast));
        }
        sb.append(String.format("总费用: %.6f元 (注意每轮输入token在递增!)\n", totalCost));
        return sb.toString();
    }

    /**
     * 预测: 基于已发生的对话, 估算"若继续聊下去, 未来10轮的总费用"。
     *
     * 核心规律: 多轮对话中, 每轮输入 token = 之前全部历史(输入+输出) + 本轮新问题,
     *          因此输入 token 会逐轮近似线性递增, 这正是"聊得越久越贵"的原因。
     *
     * 预测方法(线性外推):
     *   1. 用已发生轮次算出"每轮输入的平均增量 growth"(相邻两轮输入之差的平均);
     *   2. 用已发生轮次算出"每轮输出的平均值 avgOutput";
     *   3. 从当前最后一轮出发, 逐轮递推未来10轮的 input/output, 并累加费用。
     *
     * @param inputHistory  已发生各轮的输入 token
     * @param outputHistory 已发生各轮的输出 token
     * @return 未来10轮的预计总费用(元)
     */
    private double forecastNext10Cost(List<Integer> inputHistory, List<Integer> outputHistory) {
        int n = inputHistory.size();

        // 平均输出 token
        double avgOutput = outputHistory.stream().mapToInt(Integer::intValue).average().orElse(0);

        // 每轮输入的平均增量(相邻两轮之差); 只有1轮时用"本轮输入+输出"估算增量
        double growth;
        if (n >= 2) {
            double sumDiff = 0;
            for (int i = 1; i < n; i++) {
                sumDiff += inputHistory.get(i) - inputHistory.get(i - 1);
            }
            growth = sumDiff / (n - 1);
        } else {
            // 第1轮无法算差值: 下一轮输入 ≈ 本轮输入 + 本轮输出 + 一个新问题
            growth = inputHistory.get(0) + avgOutput;
        }

        // 从最后一轮的输入出发, 逐轮外推未来10轮
        double lastInput = inputHistory.get(n - 1);
        double forecastCost = 0;
        for (int k = 1; k <= 10; k++) {
            double predictedInput = lastInput + growth * k;
            forecastCost += predictedInput * INPUT_PRICE_PER_1K / 1000
                    + avgOutput * OUTPUT_PRICE_PER_1K / 1000;
        }
        return forecastCost;
    }

    // ========== 响应模型 ==========
    @Data
    @AllArgsConstructor
    public static class CostResult {
        private String answer;
        private int inputTokens;
        private int outputTokens;
        private int totalTokens;
        private double costYuan;
    }

    @lombok.Data
    public static class Question {
        private String text;
    }
}

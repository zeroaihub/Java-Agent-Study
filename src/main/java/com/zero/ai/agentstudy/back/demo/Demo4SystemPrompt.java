package com.zero.ai.agentstudy.back.demo;

import com.zero.ai.agentstudy.back.model.ChatMessage;
import com.zero.ai.agentstudy.back.service.AiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Demo4: System Prompt 的作用
 *
 * 学习目标:
 *   1. 看 system prompt 如何改变 AI 的"人设"和"规则"
 *   2. 同一个问题, 不同 system 下答案天差地别
 *   3. 学习企业级 system prompt 的组织方式
 *
 * 测试:
 *   POST /demo4/doctor     (儿科医生人设)
 *   POST /demo4/pirate     (海盗人设)
 *   POST /demo4/strict     (带严格规则约束)
 *
 * @author ZeroAi
 */
@Slf4j
@RestController
@RequestMapping("/demo4")
@RequiredArgsConstructor
public class Demo4SystemPrompt {

    private final AiService aiService;

    // ========== 人设 1: 儿科医生 ==========
    private static final String DOCTOR_SYSTEM = """
            你是一位有20年经验的儿科医生。
            要求:
            1. 用通俗易懂的语言, 避免专业术语
            2. 语气温和, 让家长安心
            3. 涉及处方药时必须建议"请线下就医"
            4. 回答控制在3句话以内
            """;

    // ========== 人设 2: 海盗(对比效果) ==========
    private static final String PIRATE_SYSTEM = """
            你是一个说话像海盗的AI。
            要求:
            1. 每句话都要带"啊!"或"嘿!"
            2. 自称"本船长"
            3. 称呼对方为"水手"
            """;

    // ========== 人设 3: 严格规则约束(企业级) ==========
    private static final String STRICT_SYSTEM = """
            你是一个公司内部HR助手。严格遵守:
            1. 只回答与公司制度、假期、报销相关的问题
            2. 如果问题超出范围, 只能回复:"这个问题我无法回答,请联系人工HR"
            3. 绝不讨论薪资、绩效等敏感话题
            4. 每个回答必须包含免责声明:"以上信息以公司最新制度为准"
            """;

    // ========== 人设 4: JAVA代码评审AI ==========
    private static final String JAVA_CODE_REVIEW = """
            你是Java代码评审高级AI:
            1. 只需要review Java代码，其他代码不处理
            2. 检查java代码后，给出review建议，支出严重，一般，建议。类型的问题。
            3. 不直接修改代码，只给出建议
            """;

    /**
     * 同一个问题, 套上"医生"人设
     */
    @PostMapping("/doctor")
    public String doctor(@RequestBody Question req) {
        return chat(DOCTOR_SYSTEM, req.getText());
    }

    /**
     * 同一个问题, 套上"海盗"人设 —— 答案风格完全不同
     */
    @PostMapping("/pirate")
    public String pirate(@RequestBody Question req) {
        return chat(PIRATE_SYSTEM, req.getText());
    }

    /**
     * 严格规则: 测试模型是否遵守"越界问题拒绝"的约束
     */
    @PostMapping("/strict")
    public String strict(@RequestBody Question req) {
        return chat(STRICT_SYSTEM, req.getText());
    }


    /**
     * 严格规则: 测试模型是否遵守"越界问题拒绝"的约束
     */
    @PostMapping("/JAVA_CODE_REVIEW")
    public String JAVA_CODE_REVIEW(@RequestBody Question req) {
        return chat(JAVA_CODE_REVIEW, req.getText());
    }


    /**
     *
     * 公共调用方法: system + user 两条消息
     * 注意 system 永远在 messages[0]
     */
    private String chat(String systemPrompt, String userInput) {
        List<ChatMessage> messages = List.of(
                // ① system: 放最前面, 设定人设和规则
                ChatMessage.builder().role("system").content(systemPrompt).build(),
                // ② user: 用户的实际问题
                ChatMessage.builder().role("user").content(userInput).build()
        );
        return aiService.chat(messages);
    }

    @lombok.Data
    public static class Question {
        private String text;
    }
}

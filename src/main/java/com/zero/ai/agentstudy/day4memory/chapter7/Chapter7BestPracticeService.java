package com.zero.ai.agentstudy.day4memory.chapter7;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 第七章：企业最佳实践。
 */
@Service
public class Chapter7BestPracticeService {

    public BestPracticeResponse overview() {
        return new BestPracticeResponse(lifecyclePolicies(), ragCoordination(), productionChecklist());
    }

    public List<MemoryLifecyclePolicy> lifecyclePolicies() {
        return List.of(
                new MemoryLifecyclePolicy(
                        MemoryType.SHORT_TERM_CHAT,
                        "Redis List",
                        "2 小时到 24 小时",
                        "每轮 append，并 LTRIM 保留最近 N 轮",
                        "TTL 到期自动删除，用户退出可主动删除",
                        "必须使用 userId + sessionId 隔离"
                ),
                new MemoryLifecyclePolicy(
                        MemoryType.CHAT_SUMMARY,
                        "MySQL / PostgreSQL",
                        "30 到 90 天",
                        "长对话触发摘要压缩，摘要带时间范围",
                        "过期归档或删除",
                        "摘要不能覆盖原始事实，关键字段要校验"
                ),
                new MemoryLifecyclePolicy(
                        MemoryType.USER_PROFILE,
                        "MySQL / PostgreSQL",
                        "长期保存",
                        "显式信息优先，多次证据合并，带置信度",
                        "用户可查看、编辑、删除",
                        "敏感信息默认拒绝入库"
                ),
                new MemoryLifecyclePolicy(
                        MemoryType.WORKING_STATE,
                        "Redis / 任务状态表",
                        "任务生命周期内",
                        "每个任务步骤更新 currentStep / pendingActions",
                        "任务完成后删除或归档",
                        "避免把临时状态写成长期画像"
                ),
                new MemoryLifecyclePolicy(
                        MemoryType.SEMANTIC_KNOWLEDGE,
                        "Vector DB / 文档库",
                        "随知识版本管理",
                        "文档更新后重新切分和向量化",
                        "按知识版本废弃旧索引",
                        "必须记录来源和版本"
                ),
                new MemoryLifecyclePolicy(
                        MemoryType.SENSITIVE_DATA,
                        "默认不保存",
                        "不适用",
                        "发现后脱敏或拒绝写入",
                        "立即丢弃或加密隔离",
                        "密码、token、身份证、银行卡禁止进入普通 Memory"
                )
        );
    }

    public CompressionDecision compressionDecision(CompressionRequest request) {
        if (request.estimatedTokens() >= 6000) {
            return new CompressionDecision(
                    true,
                    "summary-compression",
                    "估算 token 已接近常见上下文预算，继续全量注入会导致成本和延迟上升。",
                    "把旧消息压缩为摘要，最近 10 轮保留原文；摘要中保留姓名、目标、约束、待办。"
            );
        }
        if (request.messageCount() >= 20 && request.containsImportantFacts()) {
            return new CompressionDecision(
                    true,
                    "extract-profile-and-window",
                    "消息数较多且包含重要事实，应该把稳定信息抽取到 UserProfile。",
                    "姓名、职业、目标、偏好进入长期画像；聊天窗口只保留最近 N 轮。"
            );
        }
        if (request.messageCount() >= 20) {
            return new CompressionDecision(
                    true,
                    "message-window",
                    "消息数较多但没有明显长期事实，使用滑动窗口即可。",
                    "Redis List 使用 LTRIM 保留最近 20 条，也就是最近 10 轮。"
            );
        }
        return new CompressionDecision(
                false,
                "keep-current",
                "当前消息量和 token 量可控，暂不需要压缩。",
                "继续监控 messageCount、estimatedTokens、latency。"
        );
    }

    public ProfileUpdateDecision profileUpdateDecision(ProfileUpdateRequest request) {
        if (!StringUtils.hasText(request.field()) || !StringUtils.hasText(request.newValue())) {
            return new ProfileUpdateDecision(false, "reject",
                    "字段名或新值为空，不能更新画像。",
                    "记录一次无效更新日志即可。");
        }
        if (containsSensitive(request.newValue())) {
            return new ProfileUpdateDecision(false, "reject-sensitive",
                    "新值包含敏感信息，禁止进入长期画像。",
                    "记录安全审计日志，不要保存明文。");
        }
        if (request.userExplicitlyConfirmed()) {
            return new ProfileUpdateDecision(true, "overwrite-or-merge",
                    "用户显式确认的信息优先级最高。",
                    "写入 source=user_explicit，记录 oldValue/newValue/traceId。");
        }
        if (request.confidence() >= 0.85) {
            return new ProfileUpdateDecision(true, "merge-with-confidence",
                    "置信度较高，可以合并，但不应覆盖用户显式确认的信息。",
                    "写入 confidence 和 evidence，支持后续回滚。");
        }
        return new ProfileUpdateDecision(false, "pending-confirmation",
                "置信度不足，暂不写入长期画像。",
                "可在后续对话中询问用户确认。");
    }

    public RagCoordinationResponse ragCoordination() {
        return new RagCoordinationResponse(
                """
                User Question
                     |
                     +--> Memory Retriever  用户是谁、偏好什么、刚才聊了什么
                     |
                     +--> RAG Retriever     文档事实、制度、产品知识
                     |
                     +--> Tool Caller       实时订单、行情、审批状态
                     |
                     v
                Prompt Builder -> LLM -> Answer
                """,
                List.of(
                        "系统规则优先于一切",
                        "RAG 文档事实优先于模型常识",
                        "Tool 实时结果优先于过期 Memory",
                        "用户显式确认的 Memory 优先于模型推测",
                        "冲突时明确说明来源，不要静默合并"
                ),
                List.of(
                        "先用 userId/sessionId 读取 Memory",
                        "再用当前问题检索 RAG 文档",
                        "需要实时数据时调用 Tool",
                        "Prompt 中分区注入：用户画像、最近对话、RAG 事实、Tool 结果",
                        "回答后只抽取稳定信息更新 Memory"
                ),
                """
                【用户长期画像】
                {userProfile}

                【最近对话】
                {recentMessages}

                【知识库事实】
                {ragContext}

                【工具实时结果】
                {toolResult}

                【当前问题】
                {question}

                请优先基于知识库事实和工具实时结果回答，并结合用户画像调整表达方式。
                """
        );
    }

    private List<String> productionChecklist() {
        return List.of(
                "所有 Memory 必须按 userId/sessionId/tenantId 隔离",
                "短期记忆必须设置 TTL 和长度上限",
                "长期画像必须支持查看、编辑、删除",
                "敏感信息默认不保存，必要时脱敏和加密",
                "Memory 更新必须记录来源、置信度、更新时间",
                "RAG、Tool、Memory 的事实优先级必须明确",
                "压缩摘要要保留关键事实和时间范围",
                "串话、越权读取、画像误更新必须有测试覆盖"
        );
    }

    private boolean containsSensitive(String text) {
        return text.contains("密码")
                || text.contains("token")
                || text.contains("身份证")
                || text.contains("银行卡")
                || text.contains("验证码");
    }
}


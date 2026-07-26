package com.zero.ai.agentstudy.day4memory.chapter2;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 第二章：Memory 分类 Demo 服务。
 *
 * 这个 Demo 不调用 LLM，目的是让你先建立清晰分类意识：
 * - 当前对话上下文：Short-term Memory
 * - 稳定用户画像：Long-term Memory
 * - 当前任务状态：Working Memory
 * - 抽象知识事实：Semantic Memory
 */
@Service
public class Chapter2MemoryService {

    public MemoryMapResponse memoryMap() {
        return new MemoryMapResponse(asciiDiagram(), categories());
    }

    public List<MemoryCategoryView> categories() {
        return Arrays.stream(MemoryCategory.values())
                .map(MemoryCategoryView::from)
                .toList();
    }

    public MemoryClassifyResponse classify(String content) {
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("content 不能为空");
        }

        List<MemoryClassification> result = new ArrayList<>();
        String text = content.trim();

        if (looksLikeShortTerm(text)) {
            result.add(classification(
                    MemoryCategory.SHORT_TERM,
                    "内容依赖刚刚发生的对话或当前会话上下文。",
                    "保存到 ChatMemory，通常使用 Redis List，并设置 TTL。"
            ));
        }

        if (looksLikeLongTerm(text)) {
            result.add(classification(
                    MemoryCategory.LONG_TERM,
                    "内容描述用户稳定身份、偏好、技能或长期目标。",
                    "保存到 UserProfile，入库前建议做用户确认、去重和置信度记录。"
            ));
        }

        if (looksLikeWorking(text)) {
            result.add(classification(
                    MemoryCategory.WORKING,
                    "内容描述当前任务进度、当前步骤或待办动作。",
                    "保存到 TaskState，任务完成后归档或删除，不要无期限保存。"
            ));
        }

        if (looksLikeSemantic(text)) {
            result.add(classification(
                    MemoryCategory.SEMANTIC,
                    "内容是抽象知识、事实、规则或经验，不只属于某个用户。",
                    "进入知识库或向量库，后续由 RAG 检索，不建议塞进用户画像。"
            ));
        }

        if (result.isEmpty()) {
            result.add(classification(
                    MemoryCategory.SHORT_TERM,
                    "无法判断为稳定画像、任务状态或通用知识，默认只作为当前对话上下文处理。",
                    "先保存到短期 ChatMemory，不要直接沉淀为长期记忆。"
            ));
        }

        return new MemoryClassifyResponse(text, result);
    }

    public List<MemoryClassifyResponse> examples() {
        return List.of(
                classify("刚才用户说订单号是 A1001，想申请退款。"),
                classify("用户叫张三，是 Java 工程师，目标是成为 AI Agent 架构师。"),
                classify("当前任务是生成项目周报，已经完成进展部分，还需要补充风险。"),
                classify("Redis 适合缓存热点数据，MySQL 适合保存强事务数据。")
        );
    }

    private MemoryClassification classification(MemoryCategory category, String reason, String advice) {
        return new MemoryClassification(MemoryCategoryView.from(category), reason, advice);
    }

    private boolean looksLikeShortTerm(String text) {
        return containsAny(text, "刚才", "刚刚", "上一轮", "上面", "前面", "当前会话", "这个订单", "这个问题", "那件事");
    }

    private boolean looksLikeLongTerm(String text) {
        return containsAny(text, "我叫", "用户叫", "我是", "用户是", "职业", "工程师", "喜欢", "偏好", "目标", "希望", "擅长", "技能");
    }

    private boolean looksLikeWorking(String text) {
        return containsAny(text, "当前任务", "正在", "已完成", "还需要", "下一步", "待办", "步骤", "进度", "执行中");
    }

    private boolean looksLikeSemantic(String text) {
        return containsAny(text, "是什么", "原理", "规则", "知识", "事实", "适合", "不适合", "Redis", "MySQL", "RAG", "向量库");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String asciiDiagram() {
        return """
                Memory
                ├── Short-term Memory   最近几轮对话，解决上下文连续性
                ├── Long-term Memory    用户画像、偏好、目标、技能
                ├── Working Memory      当前任务状态、步骤、临时变量
                └── Semantic Memory     抽象知识、事实、规则、经验
                """;
    }
}


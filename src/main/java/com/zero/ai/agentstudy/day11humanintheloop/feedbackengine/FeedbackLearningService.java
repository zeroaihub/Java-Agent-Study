package com.zero.ai.agentstudy.day11humanintheloop.feedbackengine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反馈学习服务（Feedback Learning Service）——把「人的反馈」转化为「Agent 可用的经验」。
 *
 * <p>这是 Chapter 07 的灵魂。前面 {@link FeedbackEngine} 把反馈收进来了，但收进来只是
 * 「记账」，真正让 Agent「越用越聪明」的是这一层：从积累的反馈里提炼出可复用的
 * few-shot 示例（{@link FewShotExample}），并在下次执行相似任务时，把这些经验注入 Prompt，
 * 引导模型「照着人教过的正确样子」输出。</p>
 *
 * <p>学习策略（教学期实现「最实用的一档」——In-Context Learning / 提示词学习）：</p>
 * <ol>
 *   <li><b>提炼</b>：扫描 CORRECTION 类反馈（自带 ground truth），每条提炼成一条 few-shot 示例；</li>
 *   <li><b>沉淀</b>：把示例按 taskType 归档进「知识库」（教学期用内存 Map，生产用向量库）；</li>
 *   <li><b>召回</b>：执行新任务时，按 taskType 取出 top-N 高权重示例；</li>
 *   <li><b>注入</b>：把召回的示例渲染成文本，拼进 Prompt 的 few-shot 区域。</li>
 * </ol>
 *
 * <p>为什么先做 In-Context Learning 而不是「微调模型」？因为微调成本高、周期长、
 * 需要 GPU 与 MLOps 流水线；而提示词学习「零训练、即时生效、可解释、可撤回」，是绝大多数
 * 企业 Agent 落地反馈闭环的第一选择。等示例积累到一定规模，再考虑离线蒸馏 / 微调。</p>
 *
 * <p>为什么学习要与收集分离（单独一个 Service）？因为学习是「可能耗时、可批量、可异步」的：
 * 生产里往往是定时任务批量跑一遍提炼，而不是每收一条反馈就实时学。分离后，收集接口保持轻快，
 * 学习节奏可独立调度。</p>
 */
public class FeedbackLearningService {

    private final FeedbackRepository feedbackRepository;

    /**
     * 学到的示例知识库：key = taskType，value = 该类型下的示例列表。
     * <p>教学期用内存 Map；生产建议替换为向量库（按语义相似度召回，而非仅按 taskType 精确匹配）。</p>
     */
    private final Map<String, List<FewShotExample>> knowledgeBase = new ConcurrentHashMap<>();

    /** 默认召回条数上限（注入 Prompt 的示例太多会挤占上下文、稀释重点）。 */
    public static final int DEFAULT_TOP_N = 3;

    public FeedbackLearningService(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = Objects.requireNonNull(feedbackRepository, "feedbackRepository 不能为空");
    }

    // ---------------- 提炼 & 沉淀 ----------------

    /**
     * 从「单条反馈」提炼并沉淀 few-shot 示例（在线增量学习）。
     *
     * <p>只有携带 ground truth 的反馈（CORRECTION）才能提炼出示例；其他类型返回 null
     * 表示「暂无可直接学习的内容」（点赞可作正例强化，此处教学期从简只处理纠正）。</p>
     *
     * @param taskType 该反馈对应的任务类型
     * @param feedback 反馈
     * @return 提炼出的示例；无法提炼时返回 null
     */
    public FewShotExample learnFrom(String taskType, HumanFeedback feedback) {
        Objects.requireNonNull(feedback, "feedback 不能为空");
        if (!feedback.carriesGroundTruth()) {
            return null;
        }
        FewShotExample example = FewShotExample.fromCorrection(taskType, feedback);
        store(example);
        return example;
    }

    /**
     * 全量批处理学习：扫描仓储里所有 CORRECTION 反馈，提炼成示例并沉淀。
     *
     * <p>这是生产里定时任务的典型入口：每天 / 每小时跑一遍，把新积累的纠正反馈固化成经验。
     * 幂等：示例 ID 由来源反馈 ID 派生，重复跑不会产生重复示例（同 taskType 内按 ID 去重）。</p>
     *
     * @return 本次学习新增 / 更新的示例总数
     */
    public int learnAll() {
        List<HumanFeedback> corrections = feedbackRepository.findByType(FeedbackType.CORRECTION);
        int learned = 0;
        for (HumanFeedback fb : corrections) {
            // taskType 用反馈所属任务 ID 兜底（真实项目应从 AgentAction.type 带过来）
            String taskType = resolveTaskType(fb);
            FewShotExample example = FewShotExample.fromCorrection(taskType, fb);
            store(example);
            learned++;
        }
        return learned;
    }

    /**
     * 把示例存入知识库（同 exampleId 覆盖，保证幂等）。
     */
    private void store(FewShotExample example) {
        knowledgeBase.compute(example.taskType(), (type, list) -> {
            List<FewShotExample> target = (list == null) ? new ArrayList<>() : list;
            // 去重：移除同 ID 旧示例后追加新示例
            target.removeIf(e -> e.exampleId().equals(example.exampleId()));
            target.add(example);
            return target;
        });
    }

    /**
     * 解析反馈对应的 taskType：优先取 metadata 里的 taskType，否则回退到 taskId。
     */
    private String resolveTaskType(HumanFeedback fb) {
        Object t = fb.metadata().get("taskType");
        return (t != null) ? t.toString() : fb.taskId();
    }

    // ---------------- 召回 ----------------

    /**
     * 按任务类型召回 top-N 高权重示例（默认按权重降序，权重相同按时间新的优先）。
     *
     * @param taskType 任务类型
     * @param topN     召回上限
     * @return 示例列表（可能为空，不为 null）
     */
    public List<FewShotExample> recall(String taskType, int topN) {
        List<FewShotExample> list = knowledgeBase.getOrDefault(taskType, List.of());
        return list.stream()
                .sorted(Comparator
                        .comparingDouble(FewShotExample::weight).reversed()
                        .thenComparing(Comparator.comparing(FewShotExample::createdAt).reversed()))
                .limit(Math.max(0, topN))
                .toList();
    }

    /** 召回默认条数的示例。 */
    public List<FewShotExample> recall(String taskType) {
        return recall(taskType, DEFAULT_TOP_N);
    }

    // ---------------- 注入 ----------------

    /**
     * 生成可直接拼进 Prompt 的「few-shot 经验块」。
     *
     * <p>把召回的示例逐条渲染并拼接。若无任何学到的经验，返回空串（不污染 Prompt）。
     * 上层只需：{@code finalPrompt = systemPrompt + buildFewShotBlock(type) + userQuestion}。</p>
     *
     * @param taskType 任务类型
     * @return few-shot 经验块文本（可能为空串）
     */
    public String buildFewShotBlock(String taskType) {
        List<FewShotExample> examples = recall(taskType);
        if (examples.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下是过往人工纠正沉淀的正确示例，请参照其风格与标准作答：\n");
        for (FewShotExample e : examples) {
            sb.append(e.toPromptSnippet());
        }
        return sb.toString();
    }

    // ---------------- 观测 ----------------

    /** 某任务类型下已学到的示例数量。 */
    public int exampleCount(String taskType) {
        return knowledgeBase.getOrDefault(taskType, List.of()).size();
    }

    /** 知识库覆盖的任务类型数量。 */
    public int taskTypeCount() {
        return knowledgeBase.size();
    }
}
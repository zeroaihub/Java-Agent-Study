package com.zero.ai.agentstudy.day11humanintheloop.approvalapi;

import com.zero.ai.agentstudy.day11humanintheloop.approvalapi.dto.FeedbackRequest;
import com.zero.ai.agentstudy.day11humanintheloop.feedbackengine.FeedbackEngine;
import com.zero.ai.agentstudy.day11humanintheloop.feedbackengine.FeedbackLearningService;
import com.zero.ai.agentstudy.day11humanintheloop.feedbackengine.FewShotExample;
import com.zero.ai.agentstudy.day11humanintheloop.feedbackengine.HumanFeedback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 反馈 REST 控制器——把 Chapter 07 的「反馈收集 + 反馈学习」能力暴露成 HTTP 端点。
 *
 * <p>这是「人在环上（Human-on-the-loop）」闭环的对外入口：审批人/用户在控制台点「赞/踩/
 * 纠正/建议」，前端就打到这里；纠正类反馈还会顺带触发一次增量学习，把「期望的正确产出」
 * 提炼成 few-shot 示例沉淀进知识库，供下次同类任务召回注入 Prompt。</p>
 *
 * <p><b>端点一览：</b></p>
 * <pre>
 *   POST /day11/feedbacks                    提交一条反馈（按 feedbackType 路由）
 *   GET  /day11/feedbacks/task/{taskId}      查某任务的全部反馈
 *   GET  /day11/feedbacks/stats/{taskId}     查某任务的反馈统计（正向率/均分）
 *   GET  /day11/feedbacks/fewshot?taskType=  查某任务类型已学到的 few-shot 提示块
 * </pre>
 */
@RestController
@RequestMapping("/day11/feedbacks")
public class FeedbackController {

    private final FeedbackEngine feedbackEngine;
    private final FeedbackLearningService learningService;

    public FeedbackController(FeedbackEngine feedbackEngine,
                              FeedbackLearningService learningService) {
        this.feedbackEngine = feedbackEngine;
        this.learningService = learningService;
    }

    /**
     * 提交一条反馈。根据 {@code feedbackType} 路由到对应的语义化收集方法；
     * 若是纠正类反馈（CORRECTION），额外触发一次增量学习。
     *
     * @param body 反馈请求 DTO
     * @return 提交结果（含反馈 ID、类型、是否已触发学习）
     */
    @PostMapping
    public Map<String, Object> submit(@RequestBody FeedbackRequest body) {
        String reviewer = body.reviewerOrAnonymous();
        String type = body.feedbackType() == null ? "" : body.feedbackType().trim().toUpperCase();

        HumanFeedback saved = switch (type) {
            case "APPROVE", "APPROVE_RATING" ->
                    feedbackEngine.approve(body.taskId(), body.targetOutput(), reviewer, body.score());
            case "REJECT", "REJECT_RATING" ->
                    feedbackEngine.reject(body.taskId(), body.targetOutput(), reviewer, body.content());
            case "CORRECTION", "CORRECT" ->
                    feedbackEngine.correct(body.taskId(), body.targetOutput(), body.content(), reviewer);
            case "SUGGESTION", "SUGGEST" ->
                    feedbackEngine.suggest(body.taskId(), body.targetOutput(), body.content(), reviewer);
            default -> throw new IllegalArgumentException(
                    "未知反馈类型：" + body.feedbackType() + "，可选：APPROVE / REJECT / CORRECTION / SUGGESTION");
        };

        // 纠正类反馈自带 ground truth，提交后立即增量学习一条 few-shot 示例
        boolean learned = false;
        if (saved.carriesGroundTruth()) {
            FewShotExample example = learningService.learnFrom(saved.taskId(), saved);
            learned = (example != null);
        }

        return Map.of(
                "feedbackId", saved.feedbackId(),
                "type", saved.type().name(),
                "learned", learned
        );
    }

    /**
     * 查某任务的全部反馈（时间升序）。
     */
    @GetMapping("/task/{taskId}")
    public List<HumanFeedback> byTask(@PathVariable("taskId") String taskId) {
        return feedbackEngine.feedbackOf(taskId);
    }

    /**
     * 查某任务的反馈统计：正向率、平均评分、反馈条数。
     *
     * <p>这是「Agent 在某任务上表现好不好」最直观的看板数据。</p>
     */
    @GetMapping("/stats/{taskId}")
    public Map<String, Object> stats(@PathVariable("taskId") String taskId) {
        List<HumanFeedback> list = feedbackEngine.feedbackOf(taskId);
        double avg = feedbackEngine.averageScore(taskId);
        return Map.of(
                "taskId", taskId,
                "count", list.size(),
                "positiveRatio", feedbackEngine.positiveRatio(taskId),
                "averageScore", avg < 0 ? "N/A" : avg
        );
    }

    /**
     * 查某任务类型已学到的 few-shot 提示块（可直接拼进下次调用的 Prompt）。
     *
     * <p>这是反馈学习闭环的「产出出口」：前面通过纠正反馈学到的示例，在这里被召回、
     * 渲染成一段可注入的文本；若还没学到任何示例，返回空串。</p>
     *
     * @param taskType 任务类型（与提交纠正反馈时的 taskId/taskType 对应）
     * @return 可注入 Prompt 的 few-shot 文本块 + 已学到的示例数
     */
    @GetMapping("/fewshot")
    public Map<String, Object> fewShot(@RequestParam("taskType") String taskType) {
        String block = learningService.buildFewShotBlock(taskType);
        List<FewShotExample> examples = learningService.recall(taskType);
        return Map.of(
                "taskType", taskType,
                "exampleCount", examples.size(),
                "promptBlock", block
        );
    }
}
package com.zero.ai.agentstudy.day17codeagent.api;

import com.zero.ai.agentstudy.day17codeagent.api.dto.CodeTaskRequest;
import com.zero.ai.agentstudy.day17codeagent.api.dto.CodeTaskResponse;
import com.zero.ai.agentstudy.day17codeagent.core.common.CodeException;
import com.zero.ai.agentstudy.day17codeagent.core.common.CodeErrorCode;
import com.zero.ai.agentstudy.day17codeagent.core.model.CodeTaskContext;
import com.zero.ai.agentstudy.day17codeagent.core.model.RepoId;
import com.zero.ai.agentstudy.day17codeagent.core.model.TaskId;
import com.zero.ai.agentstudy.day17codeagent.core.model.TenantId;
import com.zero.ai.agentstudy.day17codeagent.core.model.UserId;
import com.zero.ai.agentstudy.day17codeagent.generator.CodeModel;
import com.zero.ai.agentstudy.day17codeagent.orchestrator.CodeTaskResult;
import com.zero.ai.agentstudy.day17codeagent.orchestrator.DefaultCodeAgentPipeline;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;
import com.zero.ai.agentstudy.day17codeagent.review.ReviewComment;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Code Agent 对外 REST 接口。
 *
 * <h3>这里的三个刻意设计</h3>
 * <ol>
 *     <li><b>租户从 Header 取，不从 Body 取</b>：{@code X-Tenant-Id} 由网关注入并验签，
 *         请求体里不存在「我是谁」这个字段，从源头堵死越权；</li>
 *     <li><b>返回结构化结果而非布尔</b>：见 {@link CodeTaskResponse}；</li>
 *     <li><b>不返回原始异常</b>：异常由 {@code KnowledgeExceptionHandler} 那一类
 *         全局处理器统一翻译，避免把堆栈泄漏给调用方。</li>
 * </ol>
 *
 * <p><b>说明</b>：本模块沿用 Day15/16 的做法——业务代码零 Spring 注解，
 * 这些 {@code @RestController} 是唯一的装配点。</p>
 *
 * @author ZeroAi
 */
public class CodeAgentController {

    private final CodeModel model;
    private final VirtualFileSystem vfs;
    private final DefaultCodeAgentPipeline pipeline;

    public CodeAgentController(CodeModel model, VirtualFileSystem vfs) {
        this.model = model;
        this.vfs = vfs;
        this.pipeline = new DefaultCodeAgentPipeline(
                model, com.zero.ai.agentstudy.day17codeagent.metering.TaskBudget.defaults());
    }

    /**
     * 提交一个编码任务。
     *
     * @param request    请求体
     * @param tenantId   租户（来自网关）
     * @param userId     用户（来自网关）
     * @param repoId     仓库
     * @param roles      角色（来自网关，逗号分隔）
     */
    public CodeTaskResponse submit(CodeTaskRequest request, String tenantId, String userId,
                                   String repoId, String roles) {
        if (request == null || request.effectiveRequirement().isEmpty()) {
            throw new CodeException(CodeErrorCode.INVALID_ARGUMENT, "requirement 不能为空");
        }
        Set<String> roleSet = roles == null || roles.isBlank()
                ? Set.of("VIEWER")
                : Set.of(roles.split(","));
        CodeTaskContext context = CodeTaskContext.of(
                TenantId.of(tenantId),
                RepoId.of(repoId),
                UserId.of(userId),
                roleSet,
                TaskId.of("task-" + UUID.randomUUID().toString().substring(0, 8)))
                .withBranch(request.effectiveBranch());

        CodeTaskResult result = pipeline.execute(
                context, vfs, request.effectiveRequirement(), request.effectiveMaxRepairRounds());
        return toResponse(result);
    }

    /** 查询任务（演示用：这里只回显状态，真实实现需要任务存储）。 */
    public CodeTaskResponse get(String taskId) {
        return CodeTaskResponse.failed(taskId, null, "NOT_STORED",
                List.of("教学实现未持久化任务，请直接读取提交接口的返回值"));
    }

    private CodeTaskResponse toResponse(CodeTaskResult result) {
        List<String> blockers = result.reviewComments().stream()
                .filter(ReviewComment::isBlocking)
                .map(c -> c.file() + ":" + c.line() + " " + c.message())
                .toList();
        List<String> concerns = result.prDraft() == null
                ? List.of("未生成 PR（可能没有成功应用的补丁）")
                : result.prDraft().concerns();
        return new CodeTaskResponse(
                result.taskId(),
                result.traceId(),
                result.status().name(),
                result.mergeable(),
                result.bundle() == null ? List.of() : result.bundle().paths(),
                result.bundle() == null ? 0 : result.bundle().totalAdded(),
                result.bundle() == null ? 0 : result.bundle().totalRemoved(),
                result.bundle() == null ? "无" : result.bundle().overallRisk().display(),
                result.compileResult() != null && result.compileResult().success(),
                result.testSummary(),
                blockers,
                concerns,
                result.prDraft() == null ? null : result.prDraft().render(),
                result.trace() == null ? 0 : result.trace().totalElapsedMs(),
                result.trace() == null ? 0 : result.trace().totalTokens());
    }
}

package com.zero.ai.agentstudy.day17codeagent.core.model;

import com.zero.ai.agentstudy.day17codeagent.core.common.CodeException;

import java.io.Serializable;
import java.util.UUID;

/**
 * Code Agent 任务上下文（不可变值对象）。
 *
 * <p>贯穿整条编码链路（理解 → 选址 → 生成 → 打补丁 → 编译 → 测试 → 评审 → 提交）
 * 的「随身携带」上下文。它同时承载：</p>
 * <ul>
 *     <li><b>多租户隔离</b>：tenantId / repoId，沙箱工作目录必须带租户前缀，否则多租户共用一台机器就是灾难；</li>
 *     <li><b>权限主体</b>：userId / roles，决定能否写文件、能否执行命令、能否提 PR；</li>
 *     <li><b>链路追踪</b>：traceId，串起每一次 LLM 调用、每一次编译、每一次测试。</li>
 * </ul>
 *
 * <p><b>为什么不可变</b>：Code Agent 会在虚拟线程里并行跑多路检索与多个补丁候选，
 * 如果上下文可变，一个子任务改了 {@code repoId}，其余子任务就会静默写到别人的仓库里。
 * record 天然不可变，derived 变更请用 {@link #withBranch(String)} 派生新实例。</p>
 *
 * @param tenantId 租户
 * @param repoId   代码仓库
 * @param userId   发起用户
 * @param roles    角色集合（RBAC）
 * @param taskId   任务 ID
 * @param branch   工作分支（隔离变更）
 * @param traceId  全链路追踪 ID
 * @author ZeroAi
 */
public record CodeTaskContext(
        TenantId tenantId,
        RepoId repoId,
        UserId userId,
        java.util.Set<String> roles,
        TaskId taskId,
        String branch,
        String traceId
) implements Serializable {

    public CodeTaskContext {
        CodeException.requireNonNull(tenantId, "tenantId");
        CodeException.requireNonNull(repoId, "repoId");
        CodeException.requireNonNull(userId, "userId");
        CodeException.requireNonNull(taskId, "taskId");
        roles = roles == null ? java.util.Set.of() : java.util.Set.copyOf(roles);
        if (branch == null || branch.isBlank()) {
            branch = "main";
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = "trace-" + UUID.randomUUID();
        }
    }

    /**
     * 便捷构建器：自动生成 traceId，分支默认 main。
     */
    public static CodeTaskContext of(TenantId tenantId, RepoId repoId, UserId userId,
                                     java.util.Set<String> roles, TaskId taskId) {
        return new CodeTaskContext(tenantId, repoId, userId, roles, taskId, null, null);
    }

    /** 派生一个新上下文，切换到指定分支（不可变的正确姿势）。 */
    public CodeTaskContext withBranch(String newBranch) {
        CodeException.requireText(newBranch, "newBranch");
        return new CodeTaskContext(tenantId, repoId, userId, roles, taskId, newBranch, traceId);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /**
     * 是否允许写仓库。<b>默认拒绝</b>：没有 {@code CODE_WRITER} 或 {@code ADMIN} 就只能只读分析。
     */
    public boolean canWrite() {
        return roles.contains("CODE_WRITER") || roles.contains("ADMIN");
    }

    /**
     * 是否允许在沙箱里执行命令。这是最危险的权限，必须显式授予。
     */
    public boolean canExecute() {
        return roles.contains("CODE_EXECUTOR") || roles.contains("ADMIN");
    }

    /** 构造沙箱工作目录名：<b>租户 + 仓库 + 任务</b>三级隔离。 */
    public String sandboxDirectoryName() {
        return tenantId.value() + "__" + repoId.value() + "__" + taskId.value();
    }

    @Override
    public String toString() {
        return "CodeTaskContext{tenant=" + tenantId + ", repo=" + repoId
                + ", task=" + taskId + ", branch=" + branch + ", trace=" + traceId + "}";
    }
}

package com.zero.ai.agentstudy.day15knowledgeplatform.sync.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;

import java.time.Instant;

/**
 * 同步任务（增量同步的执行单元）。
 *
 * <p><b>为什么要把「同步」建模成一个有状态的实体，而不是一个方法调用？</b>
 * 因为同步是一个<b>长时运行、可能失败、需要重试、需要审计</b>的过程。
 * 一个方法调用做不到这些：
 * 进程重启了，调用就丢了；失败了，没人知道该从哪重来；
 * 想查"过去一周同步了多少次、失败了多少次"，无从查起。
 * 建模成实体后，它就有了自己的生命周期与状态机，
 * 可以被持久化、被查询、被重试——这正是 Day12 长时运行 Agent 的思路在知识平台里的复用。</p>
 *
 * <p><b>三个动作（ADD / UPDATE / DELETE）必须齐全，缺一不可。</b>
 * 大部分人只实现了前两个，于是第三个动作（删除）永远不会被触发，
 * 这就是「幽灵知识」的根源——源端删了，本地还在。</p>
 *
 * @author ZeroAi
 */
public final class SyncJob {

    /**
     * 同步动作。
     *
     * @author ZeroAi
     */
    public enum Action {
        /** 新增：源端有、本地无。 */
        ADD,
        /** 更新：源端与本地指纹不一致。 */
        UPDATE,
        /** 删除：源端已无、本地仍有。<b>最容易被遗漏，也最危险。</b> */
        DELETE,
        /** 无变化：指纹一致，跳过。 */
        SKIP
    }

    /**
     * 同步状态。
     *
     * @author ZeroAi
     */
    public enum Status {
        PENDING, RUNNING, SUCCEEDED, FAILED
    }

    private final String jobId;
    private final TenantId tenantId;
    private final KnowledgeBaseId knowledgeBaseId;
    private final DocumentId documentId;
    private final String sourceKey;
    private final Action action;
    private final ContentFingerprint fingerprint;
    private final Instant createdAt;

    private Status status;
    private String message;
    private int attempt;

    private SyncJob(TenantId tenantId, KnowledgeBaseId kbId, DocumentId documentId,
                    String sourceKey, Action action, ContentFingerprint fingerprint) {
        this.jobId = "sync-" + java.util.UUID.randomUUID();
        this.tenantId = KnowledgeException.requireNonNull(tenantId, "tenantId");
        this.knowledgeBaseId = KnowledgeException.requireNonNull(kbId, "knowledgeBaseId");
        this.documentId = documentId;
        this.sourceKey = KnowledgeException.requireNonNull(sourceKey, "sourceKey");
        this.action = KnowledgeException.requireNonNull(action, "action");
        this.fingerprint = fingerprint;
        this.createdAt = Instant.now();
        this.status = Status.PENDING;
        this.attempt = 0;
    }

    public static SyncJob of(TenantId tenantId, KnowledgeBaseId kbId, DocumentId documentId,
                             String sourceKey, Action action, ContentFingerprint fingerprint) {
        return new SyncJob(tenantId, kbId, documentId, sourceKey, action, fingerprint);
    }

    public void markRunning() {
        if (status != Status.PENDING && status != Status.FAILED) {
            throw KnowledgeException.of(KnowledgeErrorCode.OPERATION_NOT_ALLOWED,
                    "同步任务 " + jobId + " 当前状态为 " + status + "，不能启动");
        }
        this.status = Status.RUNNING;
        this.attempt++;
    }

    public void markSucceeded(String msg) {
        if (status != Status.RUNNING) {
            throw KnowledgeException.of(KnowledgeErrorCode.OPERATION_NOT_ALLOWED,
                    "同步任务 " + jobId + " 未在运行中，无法标记成功");
        }
        this.status = Status.SUCCEEDED;
        this.message = msg;
    }

    public void markFailed(String reason) {
        this.status = Status.FAILED;
        this.message = reason;
    }

    /**
     * 是否还有重试机会。
     *
     * <p><b>为什么限制重试次数？</b>
     * 因为有一类失败是<b>永久性</b>的：文件损坏、格式不支持、内容违法合规策略。
     * 对它们重试一万次也只是浪费资源。
     * 区分「可重试」与「不可重试」失败，是任务系统的基本功。</p>
     */
    public boolean canRetry(int maxAttempts) {
        return status == Status.FAILED && attempt < maxAttempts;
    }

    public String jobId() {
        return jobId;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public KnowledgeBaseId knowledgeBaseId() {
        return knowledgeBaseId;
    }

    public DocumentId documentId() {
        return documentId;
    }

    public String sourceKey() {
        return sourceKey;
    }

    public Action action() {
        return action;
    }

    public ContentFingerprint fingerprint() {
        return fingerprint;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Status status() {
        return status;
    }

    public String message() {
        return message;
    }

    public int attempt() {
        return attempt;
    }

    @Override
    public String toString() {
        return "SyncJob{" + action + ", " + sourceKey
                + ", status=" + status + ", attempt=" + attempt + "}";
    }
}

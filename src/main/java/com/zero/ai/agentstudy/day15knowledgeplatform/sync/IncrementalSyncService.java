package com.zero.ai.agentstudy.day15knowledgeplatform.sync;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.sync.model.ContentFingerprint;
import com.zero.ai.agentstudy.day15knowledgeplatform.sync.model.SyncJob;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 增量同步服务。
 *
 * <p><b>核心思想极其朴素，但威力巨大：比对指纹，只处理变化的。</b></p>
 *
 * <p>为什么这件事值得单独写一章？看一组数字就明白了：
 * 假设一个知识库有 10 万篇文档，每天变动 200 篇（0.2%）。</p>
 * <ul>
 *     <li><b>全量重建</b>：每天 10 万次解析 + 100 万次 Embedding 调用 ≈ 数千元/天；</li>
 *     <li><b>增量同步</b>：每天 200 次解析 + 2000 次 Embedding ≈ 十几元/天。</li>
 * </ul>
 * <b>两个数量级的成本差距，而且全量重建期间服务还不可用。</b>
 * 这就是为什么所有成熟的 RAG 系统都必须做增量同步。</p>
 *
 * <p><b>本类最重要的方法是 {@link #planJobs}——它产出「同步计划」。</b>
 * 为什么不直接在扫描时执行？因为<b>「决定做什么」和「做」必须分开</b>：
 * <ul>
 *     <li>计划可以先打印出来人工确认（"这次要删 3000 篇，确定吗？"）；</li>
 *     <li>计划可以持久化，执行中断后从断点继续；</li>
 *     <li>计划可以限流（一次不要提交 10 万个任务）；</li>
 *     <li>计划可以审计（"上周三到底删了哪些？"）。</li>
 * </ul>
 * <b>把「决策」变成一份可检查的数据，是让自动化系统变得可信的关键。</b></p>
 *
 * @author ZeroAi
 */
public class IncrementalSyncService {

    /** 默认最大重试次数。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /**
     * 已同步状态记录：sourceKey → 指纹 + 文档 ID。
     *
     * <p>这就是增量同步所需的全部"记忆"：<b>上次同步时，这个源是什么样子的。</b></p>
     */
    private final Map<String, SyncState> syncedState = new LinkedHashMap<>();

    /**
     * 生成同步计划：比对「源端现状」与「本地已同步状态」，产出待执行的任务列表。
     *
     * <p>算法分三步，顺序不能乱：</p>
     * <ol>
     *     <li><b>遍历源端</b>：有新增（本地无记录）→ ADD；指纹不同 → UPDATE；相同 → SKIP；</li>
     *     <li><b>反查本地</b>：本地有记录但源端已经没有了 → <b>DELETE</b>；</li>
     *     <li>汇总返回。</li>
     * </ol>
     *
     * <p><b>第 2 步是整个增量同步里最容易被漏掉的一步，也是最危险的一步。</b>
     * 人的直觉是"同步 = 把新的拿过来"，于是只写了第 1 步。
     * 但同步的完整定义是"让本地与源端<b>一致</b>"——不一致是双向的，
     * 少了第 2 步，删掉的文档会永远留在向量库里，变成检索得到的「幽灵知识」。</p>
     *
     * @param tenantId      租户
     * @param kbId          知识库
     * @param currentSource 源端现状：sourceKey → (内容指纹, 文档 ID)
     * @return 同步计划
     */
    public List<SyncJob> planJobs(TenantId tenantId, KnowledgeBaseId kbId,
                                  Map<String, SourceItem> currentSource) {
        KnowledgeException.requireNonNull(tenantId, "tenantId");
        KnowledgeException.requireNonNull(kbId, "knowledgeBaseId");
        KnowledgeException.requireNonNull(currentSource, "currentSource");

        List<SyncJob> jobs = new ArrayList<>();

        // ---- 第 1 步：遍历源端 ----
        for (Map.Entry<String, SourceItem> e : currentSource.entrySet()) {
            String sourceKey = e.getKey();
            SourceItem item = e.getValue();
            SyncState known = syncedState.get(stateKey(tenantId, kbId, sourceKey));

            SyncJob.Action action;
            if (known == null) {
                action = SyncJob.Action.ADD;
            } else if (!known.fingerprint().equals(item.fingerprint())) {
                action = SyncJob.Action.UPDATE;
            } else {
                action = SyncJob.Action.SKIP;
            }

            if (action != SyncJob.Action.SKIP) {
                jobs.add(SyncJob.of(tenantId, kbId,
                        known == null ? null : known.documentId(),
                        sourceKey, action, item.fingerprint()));
            }
        }

        // ---- 第 2 步：反查本地，找出源端已删除的 ----
        String prefix = stateKey(tenantId, kbId, "");
        for (Map.Entry<String, SyncState> e : syncedState.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue; // 不属于本次同步的知识库
            }
            String sourceKey = e.getKey().substring(prefix.length());
            if (!currentSource.containsKey(sourceKey)) {
                jobs.add(SyncJob.of(tenantId, kbId, e.getValue().documentId(),
                        sourceKey, SyncJob.Action.DELETE, e.getValue().fingerprint()));
            }
        }

        return List.copyOf(jobs);
    }

    /**
     * 提交同步计划（把计划落库，等待执行器消费）。
     *
     * <p>这里刻意只更新「已同步状态」的登记，而不执行真正的入库——
     * 因为<b>索引写入属于编排层</b>，应由第 13 章的 API 层调用
     * {@code KnowledgeIndexer} 来完成。
     * 本服务只负责回答"该做什么"，不负责"怎么做"。
     * 职责清晰，才能让每一层都能被独立测试。</p>
     */
    public void commit(TenantId tenantId, KnowledgeBaseId kbId, List<SyncJob> jobs) {
        KnowledgeException.requireNonNull(jobs, "jobs");
        for (SyncJob job : jobs) {
            if (job.status() != SyncJob.Status.SUCCEEDED) {
                continue;
            }
            String key = stateKey(tenantId, kbId, job.sourceKey());
            switch (job.action()) {
                case ADD, UPDATE -> syncedState.put(key,
                        new SyncState(job.fingerprint(), job.documentId()));
                // DELETE 必须真的把状态删掉，否则下次扫描又会把它判成 ADD，无限循环
                case DELETE -> syncedState.remove(key);
                case SKIP -> {
                }
            }
        }
    }

    /**
     * 源端快照条目。
     *
     * @param fingerprint 内容指纹
     * @param documentId  对应的本地文档 ID（新增时可为 null）
     * @author ZeroAi
     */
    public record SourceItem(ContentFingerprint fingerprint, DocumentId documentId) {
    }

    /**
     * 本地已同步状态。
     *
     * @author ZeroAi
     */
    private record SyncState(ContentFingerprint fingerprint, DocumentId documentId) {
    }

    /**
     * 状态表键。带 tenant 与 kb 前缀，天然隔离。
     */
    private static String stateKey(TenantId tenantId, KnowledgeBaseId kbId, String sourceKey) {
        return tenantId.value() + "/" + kbId.value() + "/" + sourceKey;
    }

    /**
     * 已同步条目数（测试与运维用）。
     */
    public int syncedCount() {
        return syncedState.size();
    }
}

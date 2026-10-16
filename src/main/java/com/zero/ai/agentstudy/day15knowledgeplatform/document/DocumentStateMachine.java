package com.zero.ai.agentstudy.day15knowledgeplatform.document;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 文档生命周期状态机。
 *
 * <p><b>为什么要显式建模状态机，而不是写 {@code document.setStatus(x)}？</b></p>
 *
 * <p>因为「置状态字段」会允许任何状态跳到任何状态。真实事故往往是：
 * 一个重试任务把已 DELETED 的文档又置回 INDEXED，于是「幽灵知识」复活，
 * 用户在早就删除的文档里检索到了内容——这是最典型的删除不同步 Bug。</p>
 *
 * <p>状态机把合法流转写进一张转移表，非法流转在第一时间抛异常，
 * <b>用代码把「不可能发生的事」变成「编译期/运行期立刻失败」</b>。</p>
 *
 * <p>实现上用 {@link EnumMap} 而非 {@code Map<String, Set<String>>}：
 * 枚举做 key 有更好的性能与类型安全，且不存在拼错字符串导致规则静默失效的可能。</p>
 *
 * @author ZeroAi
 */
public final class DocumentStateMachine {

    private static final Map<DocumentStatus, Set<DocumentStatus>> TRANSITIONS =
            new EnumMap<>(DocumentStatus.class);

    static {
        // 正常流水线：上传 → 解析 → 切分 → 向量化 → 入库
        allow(DocumentStatus.UPLOADED, DocumentStatus.PARSING, DocumentStatus.FAILED, DocumentStatus.DELETED);
        allow(DocumentStatus.PARSING, DocumentStatus.PARSED, DocumentStatus.FAILED, DocumentStatus.DELETED);
        allow(DocumentStatus.PARSED, DocumentStatus.CHUNKING, DocumentStatus.FAILED, DocumentStatus.DELETED);
        allow(DocumentStatus.CHUNKING, DocumentStatus.CHUNKED, DocumentStatus.FAILED, DocumentStatus.DELETED);
        allow(DocumentStatus.CHUNKED, DocumentStatus.EMBEDDING, DocumentStatus.FAILED, DocumentStatus.DELETED);
        allow(DocumentStatus.EMBEDDING, DocumentStatus.EMBEDDED, DocumentStatus.FAILED, DocumentStatus.DELETED);
        allow(DocumentStatus.EMBEDDED, DocumentStatus.INDEXED, DocumentStatus.FAILED, DocumentStatus.DELETED);

        // 已入库的文档可以重新走流水线（例如换了 Embedding 模型需要全量重建）
        allow(DocumentStatus.INDEXED, DocumentStatus.PARSING, DocumentStatus.DELETED);

        // 失败可重试：只允许回到流水线起点，不允许「跳过解析直接切分」
        allow(DocumentStatus.FAILED, DocumentStatus.PARSING, DocumentStatus.DELETED);

        // 删除是单向门，出去就回不来
        allow(DocumentStatus.DELETED);
    }

    private DocumentStateMachine() {
    }

    private static void allow(DocumentStatus from, DocumentStatus... targets) {
        // EnumSet.copyOf(Collection) 在传入空集合且该集合不是 EnumSet 时会抛
        // IllegalArgumentException，因此「终点状态（无任何出边）」必须走 noneOf 分支。
        if (targets == null || targets.length == 0) {
            TRANSITIONS.put(from, EnumSet.noneOf(DocumentStatus.class));
            return;
        }
        TRANSITIONS.put(from, EnumSet.copyOf(java.util.Arrays.asList(targets)));
    }

    /**
     * 判断能否从 from 流转到 to。
     */
    public static boolean canFire(DocumentStatus from, DocumentStatus to) {
        Set<DocumentStatus> targets = TRANSITIONS.get(from);
        return targets != null && targets.contains(to);
    }

    /**
     * 返回 from 状态下所有合法的下一步。
     */
    public static Set<DocumentStatus> allowedTransitions(DocumentStatus from) {
        Set<DocumentStatus> targets = TRANSITIONS.get(from);
        if (targets == null) {
            return Collections.emptySet();
        }
        // 转移表在静态块中初始化完成后不再变更，直接返回只读视图即可
        return Collections.unmodifiableSet(targets);
    }

    /**
     * 执行状态流转，非法流转直接抛异常。
     *
     * @return 流转后的目标状态（便于调用方链式赋值）
     * @throws KnowledgeException 状态非法时抛 {@link KnowledgeErrorCode#DOCUMENT_STATE_ILLEGAL}
     */
    public static DocumentStatus fire(DocumentStatus from, DocumentStatus to) {
        if (!canFire(from, to)) {
            throw new KnowledgeException(KnowledgeErrorCode.DOCUMENT_STATE_ILLEGAL,
                    "文档状态无法从 " + from.description() + " 流转到 " + to.description()
                            + "，允许的下一步: " + allowedTransitions(from));
        }
        return to;
    }
}

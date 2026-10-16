package com.zero.ai.agentstudy.day15knowledgeplatform.document.model;

/**
 * 文档生命周期状态。
 *
 * <p>一份文档从上传到可被检索，要穿过解析、切分、向量化、入库四个阶段。
 * 我们把每个阶段都建模为「进行中 + 已完成」两个状态，而不是只保留「已完成」，原因有三：</p>
 * <ol>
 *     <li><b>可断点续跑</b>：进程崩溃后能知道卡在 PARSING 还是 EMBEDDING，从那一步继续，不必从头再来。</li>
 *     <li><b>可观测</b>：看板能直接统计「各状态文档数」，卡住的文档一目了然。</li>
 *     <li><b>可并发控制</b>：进行中状态天然是幂等锁——同一文档不会被两个线程重复处理。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public enum DocumentStatus {

    /** 已上传，等待解析 */
    UPLOADED("已上传", false),
    /** 解析中 */
    PARSING("解析中", false),
    /** 已解析出纯文本 */
    PARSED("已解析", false),
    /** 切分中 */
    CHUNKING("切分中", false),
    /** 已切分为 Chunk */
    CHUNKED("已切分", false),
    /** 向量化中 */
    EMBEDDING("向量化中", false),
    /** 已完成向量化 */
    EMBEDDED("已向量化", false),
    /** 已写入向量库，可被检索（正常终点） */
    INDEXED("已入库", true),
    /** 处理失败，可重试（异常终点） */
    FAILED("处理失败", true),
    /** 已删除，其 Chunk 与向量应被级联清理（终点） */
    DELETED("已删除", true);

    private final String description;
    private final boolean terminal;

    DocumentStatus(String description, boolean terminal) {
        this.description = description;
        this.terminal = terminal;
    }

    public String description() {
        return description;
    }

    /**
     * 是否为终点状态（不再自动流转，只能被人工触发重试或删除）。
     */
    public boolean isTerminal() {
        return terminal;
    }
}

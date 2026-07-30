package com.zero.ai.agentstudy.day13officeagent.officesummary.application;

import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.DocumentIR;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.port.ModelPort;
import com.zero.ai.agentstudy.day13officeagent.officesummary.domain.OutlineToIrMapper;
import com.zero.ai.agentstudy.day13officeagent.officesummary.domain.SummaryOutline;
import org.springframework.stereotype.Service;

/**
 * 文档总结应用服务（DocumentSummaryService）——officesummary 模块的编排核心。
 *
 * <p><b>职责：</b> 把"一段原始数据/长文本"变成"一份结构化文档 IR"。它编排了三步：
 * (1) 组装面向大模型的提示词；(2) 通过 {@link ModelPort} 结构化输出得到扁平的
 * {@link SummaryOutline}；(3) 用 {@link OutlineToIrMapper} 把大纲映射成完整的 {@link DocumentIR}。
 * 拿到 IR 之后，交给哪种渲染器（Word/PPT/PDF）就与本服务无关了——这正是 IR 解耦的红利。</p>
 *
 * <p><b>三个典型场景由三个方法承载：</b> 通用文档总结、销售数据周报、会议纪要。它们共享同一条
 * "提示词 → 结构化输出 → 映射 IR"链路，只是提示词模板不同。把提示词工程集中在应用层，
 * 领域层（Mapper/Outline）保持与 LLM 无关，是清晰的关注点分离。</p>
 *
 * <p><b>依赖倒置：</b> 本服务只依赖 {@link ModelPort} 抽象，对底层是 OpenAI 还是本地模型一无所知，
 * 测试时可注入一个返回固定 {@code SummaryOutline} 的假实现，完全脱离网络与真实模型。</p>
 *
 * @author zero
 */
@Service
public class DocumentSummaryService {

    private final ModelPort modelPort;

    /**
     * 构造应用服务。
     *
     * @param modelPort 大模型出站端口
     */
    public DocumentSummaryService(ModelPort modelPort) {
        this.modelPort = modelPort;
    }

    /**
     * 通用文档总结：把一段长文本压缩为结构化的总结文档。
     *
     * @param sourceText 待总结的原始长文本
     * @param author     生成者
     * @param tenantId   租户标识
     * @return 结构化的文档 IR
     */
    public DocumentIR summarize(String sourceText, String author, String tenantId) {
        String prompt = """
                请阅读下面的原始内容，提炼成一份结构清晰的总结文档。
                请以大纲形式组织：一个主标题、一句话摘要作为副标题、若干章节，
                每个章节含标题、若干正文段落，必要时列出要点。
                只提炼原文已有的信息，不要编造。

                【原始内容】
                %s
                """.formatted(safe(sourceText));

        SummaryOutline outline = modelPort.generateStructured(prompt, SummaryOutline.class);
        return OutlineToIrMapper.map(outline, author, tenantId);
    }

    /**
     * 销售数据周报：把结构化的销售数据摘要转成一份专业周报。
     *
     * <p>这是终极场景"根据昨天销售数据生成一份周报"的核心步骤——本方法产出 IR 后，
     * 上层 Pipeline 再决定渲染成 Word 存档 + PPT 发送。</p>
     *
     * @param salesDataDigest 结构化的销售数据摘要（如"本周 GMV 1200 万，环比 +8%..."）
     * @param periodLabel     周期标签（如"2026 年第 30 周"）
     * @param author          生成者
     * @param tenantId        租户标识
     * @return 周报文档 IR
     */
    public DocumentIR generateWeeklySalesReport(String salesDataDigest, String periodLabel,
                                                String author, String tenantId) {
        String prompt = """
                你是一名资深销售运营分析师。请根据下面的销售数据，撰写一份面向销售总监的%s销售周报。
                要求以大纲形式组织：
                - 主标题：包含周期与"销售周报"字样；
                - 副标题：一句话概括本周核心结论（如整体走势与关键变化）；
                - 章节建议包含：核心指标概览、亮点与增长、风险与下滑、下周行动建议；
                - 每个章节用正文段落做分析，用要点列出关键数字或待办；
                - 只使用给定数据中的数字，不要编造。

                【销售数据】
                %s
                """.formatted(safe(periodLabel), safe(salesDataDigest));

        SummaryOutline outline = modelPort.generateStructured(prompt, SummaryOutline.class);
        return OutlineToIrMapper.map(outline, author, tenantId);
    }

    /**
     * 会议纪要：把会议转写/速记整理成规范的会议纪要文档。
     *
     * @param transcript 会议转写或速记原文
     * @param meetingTitle 会议主题
     * @param author       生成者（记录人）
     * @param tenantId     租户标识
     * @return 会议纪要文档 IR
     */
    public DocumentIR generateMeetingMinutes(String transcript, String meetingTitle,
                                             String author, String tenantId) {
        String prompt = """
                请把下面的会议速记整理成一份规范的会议纪要《%s》。
                要求以大纲形式组织：
                - 主标题为会议主题；
                - 副标题为会议的一句话结论或目的；
                - 章节建议包含：会议背景、讨论要点、达成的决议、待办事项（含责任人）；
                - 讨论要点与待办用要点列表呈现；
                - 忠实于速记内容，不要臆测未提及的责任人或时间。

                【会议速记】
                %s
                """.formatted(safe(meetingTitle), safe(transcript));

        SummaryOutline outline = modelPort.generateStructured(prompt, SummaryOutline.class);
        return OutlineToIrMapper.map(outline, author, tenantId);
    }

    /**
     * 空值兜底，避免把 {@code null} 拼进提示词。
     *
     * @param s 输入
     * @return 非 null 字符串
     */
    private String safe(String s) {
        return s == null ? "" : s;
    }
}
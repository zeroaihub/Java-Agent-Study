package com.zero.ai.agentstudy.day13officeagent.officesummary.domain;

import java.util.List;

/**
 * 文档大纲（SummaryOutline）——大模型结构化输出的<b>目标类型</b>。
 *
 * <p><b>为什么不直接让模型产出 {@code DocumentIR}？</b> {@code DocumentIR} 是一棵富含 sealed
 * 接口、内嵌 record、防御性拷贝与私有构造器的领域聚合根——它为"渲染"而生，字段繁复、约束严格，
 * 并不适合直接作为 JSON 反序列化目标（私有构造器 + 无 setter 会让 Jackson 束手无策，
 * 而暴露构造器又会破坏领域不变式）。</p>
 *
 * <p><b>本类是"结构化输出专用 DTO"：</b> 它只保留大模型真正需要填的、扁平且易反序列化的字段——
 * 标题、副标题、若干章节，每个章节含标题、正文段落与要点列表。它是一个纯 record，字段简单，
 * Spring AI 能可靠地为它注入 JSON Schema 并反序列化。得到 {@code SummaryOutline} 后，再由
 * {@link OutlineToIrMapper} 映射成结构完整的 {@code DocumentIR}。</p>
 *
 * <p>这种"LLM 产出简单 DTO → 领域代码映射为复杂聚合"的分层，是结构化输出落地的关键工程经验：
 * <b>让模型只做它擅长的事（填扁平字段），把结构组装留给确定性的代码。</b></p>
 *
 * @param title    文档主标题
 * @param subtitle 副标题/摘要，可为空
 * @param sections 章节列表，按顺序渲染
 * @author zero
 */
public record SummaryOutline(String title, String subtitle, List<Section> sections) {

    public SummaryOutline {
        title = title == null ? "" : title;
        subtitle = subtitle == null ? "" : subtitle;
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    /**
     * 章节（Section）——一个标题 + 若干正文段落 + 若干要点。
     *
     * @param heading    章节标题
     * @param paragraphs 正文段落（每个元素是一段），可为空
     * @param bullets    要点列表（无序），可为空
     * @author zero
     */
    public record Section(String heading, List<String> paragraphs, List<String> bullets) {

        public Section {
            heading = heading == null ? "" : heading;
            paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
            bullets = bullets == null ? List.of() : List.copyOf(bullets);
        }

        /**
         * 快捷工厂：仅含标题与正文段落的章节。
         *
         * @param heading    标题
         * @param paragraphs 正文段落
         * @return 章节实例
         */
        public static Section of(String heading, List<String> paragraphs) {
            return new Section(heading, paragraphs, List.of());
        }
    }

    /**
     * 判断大纲是否为空（模型未产出任何有效内容）。
     *
     * @return 无标题且无章节时返回 {@code true}
     */
    public boolean isEmpty() {
        return title.isBlank() && sections.isEmpty();
    }
}
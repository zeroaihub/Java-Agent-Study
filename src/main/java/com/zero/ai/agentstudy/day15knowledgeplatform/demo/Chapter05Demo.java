package com.zero.ai.agentstudy.day15knowledgeplatform.demo;

import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.ChunkingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.TokenEstimator;
import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.model.Chunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.port.ChunkStrategy;
import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.strategy.FixedSizeChunkStrategy;
import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.strategy.SemanticChunkStrategy;
import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.strategy.StructuredChunkStrategy;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentFormat;

import java.util.List;

/**
 * 第 5 章 Demo：knowledge-chunk 切分策略对比。
 *
 * <p>零依赖可运行：{@code java ... Chapter05Demo}。</p>
 *
 * <p>同一份 Markdown 制度文档，分别用三种策略切分，直观对比：</p>
 * <ul>
 *     <li>定长：切片均匀，但会切断句子</li>
 *     <li>语义：边界落在句末，大小不均</li>
 *     <li>结构化：按标题切，自带章节路径，召回质量最高</li>
 * </ul>
 *
 * @author ZeroAi
 */
public class Chapter05Demo {

    private static final String DOC = """
            # 员工报销管理制度

            本制度自 2026 年 1 月 1 日起执行，适用于全体正式员工。

            ## 一、住宿标准

            总监级别每晚上限 800 元。经理级别每晚上限 500 元。
            员工级别每晚上限 350 元。一线城市上浮 20%。

            ## 二、交通标准

            高铁二等座，飞机经济舱。总监级别可乘坐高铁一等座。
            市内交通凭票据实报实销，单日上限 200 元。

            ## 三、审批流程

            单次报销金额低于 1000 元由直属主管审批。
            高于 1000 元需部门负责人与财务双人审批。
            高于 10000 元需上报分管副总审批。

            ## 四、违规处理

            虚报费用一经查实，全额追回并计入绩效考核。
            """;

    public static void main(String[] args) {
        section("第 5 章 · knowledge-chunk 切分策略");

        System.out.println("原文字符数: " + DOC.length() + "，估算 Token 数: " + TokenEstimator.estimate(DOC));

        // 三种策略用同一份文档对比；定长/语义刻意取较小的窗口，
        // 否则 293 字的示例文档只会被切成 1 片，看不出策略差异。
        compare("定长切分（120 字 / 重叠 20 字）", new FixedSizeChunkStrategy(120, 20));
        compare("语义切分（按句边界，最长 120）", new SemanticChunkStrategy(120, 40));
        compare("结构化切分（按 # 标题，最长 800）", new StructuredChunkStrategy(800, 50));

        step("自动策略选择");
        for (DocumentFormat format : DocumentFormat.values()) {
            System.out.println("  " + format + " → " + ChunkingService.selectStrategy(format).name());
        }

        step("为什么必须带 overlap：边界信息被切断的后果");
        String text = "报销标准：总监级别每晚上限 800 元，经理级别 500 元。";
        List<String> noOverlap = new FixedSizeChunkStrategy(20, 0).split(text);
        List<String> withOverlap = new FixedSizeChunkStrategy(20, 8).split(text);
        System.out.println("  原文: " + text);
        System.out.println("  无重叠: " + noOverlap);
        System.out.println("  有重叠: " + withOverlap);
        System.out.println("  → 「800 元」被切断时，无重叠的两片都失去关键语义；重叠可救回边界信息。");

        step("Chunk 装配（租户/文档/序号/元数据全部焊死在切片上）");
        var doc = com.zero.ai.agentstudy.day15knowledgeplatform.core.tenant.TenantContext.runWithTenant(
                com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId.of("acme"),
                () -> com.zero.ai.agentstudy.day15knowledgeplatform.document.model.Document.upload(
                        com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId.of("acme"),
                        com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId.newId(),
                        com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId.newId(),
                        "报销制度.md",
                        DOC.getBytes(),
                        com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata.of(
                                java.util.Map.of("department", "finance"))));
        List<Chunk> chunks = new ChunkingService()
                .chunkText(doc, com.zero.ai.agentstudy.day15knowledgeplatform.parser.model.ParsedDocument.of(DOC),
                        new StructuredChunkStrategy(800, 50));
        for (Chunk c : chunks) {
            System.out.println("  " + c.id() + " seq=" + c.sequence()
                    + " chars=" + c.charCount() + " tokens≈" + c.tokenEstimate()
                    + " | " + c.preview(40));
        }
        System.out.println("  租户=" + chunks.get(0).tenantId() + "，部门="
                + chunks.get(0).metadata().getString("department").orElse("?"));

        System.out.println("\n第 5 章 Demo 结束。");
    }

    private static void compare(String title, ChunkStrategy strategy) {
        step(title);
        List<String> pieces = strategy.split(DOC);
        System.out.println("  切片数=" + pieces.size()
                + "，平均长度=" + pieces.stream().mapToInt(String::length).sum() / Math.max(1, pieces.size()));
        for (int i = 0; i < pieces.size(); i++) {
            String p = pieces.get(i).replaceAll("\\s+", " ").trim();
            System.out.println("  [" + i + "] (" + p.length() + "字) "
                    + (p.length() > 46 ? p.substring(0, 46) + "…" : p));
        }
    }

    private static void section(String title) {
        System.out.println("==================================================");
        System.out.println(title);
        System.out.println("==================================================");
    }

    private static void step(String text) {
        System.out.println("\n--- " + text + " ---");
    }
}

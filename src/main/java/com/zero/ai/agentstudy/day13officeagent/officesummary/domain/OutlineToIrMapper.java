package com.zero.ai.agentstudy.day13officeagent.officesummary.domain;

import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.DocumentIR;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.DocumentMetadata;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.HeadingBlock;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.ListBlock;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.ParagraphBlock;

import java.util.List;

/**
 * 大纲到 IR 的映射器（OutlineToIrMapper）——把大模型产出的扁平 {@link SummaryOutline}
 * 组装成结构完整、可直接渲染的 {@link DocumentIR}。
 *
 * <p><b>为什么这一步由确定性代码而非大模型完成？</b> 结构组装（标题层级怎么排、要点用有序还是无序、
 * 空段落要不要跳过）是有明确规则的确定性工作。把它交给代码，既能保证输出结构永远合法，
 * 又能把大模型从"既要填内容又要管结构"的双重负担中解放出来——模型只需专注填好文字，
 * 结构正确性由本类兜底。这正是"确定性外壳 + 概率性内核"的工程范式。</p>
 *
 * <p>映射规则：主标题 → H1；副标题非空 → 一段正文；每个章节标题 → H2，其正文段落逐段展开，
 * 要点列表 → 无序 {@code ListBlock}。空字符串一律跳过，避免渲染出空标题/空段落。</p>
 *
 * <p>本类是纯函数式的领域服务：无状态、无副作用、不依赖任何框架，因此可被自由复用与单元测试。</p>
 *
 * @author zero
 */
public final class OutlineToIrMapper {

    private OutlineToIrMapper() {
        // 工具类，禁止实例化
    }

    /**
     * 把大纲映射为文档 IR。
     *
     * @param outline  大模型产出的大纲，允许为 {@code null}（视为空文档）
     * @param author   文档作者/生成者
     * @param tenantId 租户标识，用于多租户隔离
     * @return 组装完成的不可变文档 IR
     */
    public static DocumentIR map(SummaryOutline outline, String author, String tenantId) {
        DocumentIR.Builder builder = DocumentIR.builder();

        if (outline == null || outline.isEmpty()) {
            // 兜底：模型未产出有效内容时，返回一份带默认标题的空文档，绝不返回 null
            DocumentMetadata empty = DocumentMetadata.of("（无内容）", author).withTenant(tenantId);
            return builder.metadata(empty).heading(1, "（无内容）").build();
        }

        // 元数据：标题取大纲主标题，作者与租户由调用方传入
        DocumentMetadata metadata = DocumentMetadata.of(outline.title(), author)
                .withTenant(tenantId);
        builder.metadata(metadata);

        // 主标题 → H1
        if (!outline.title().isBlank()) {
            builder.block(HeadingBlock.of(1, outline.title()));
        }
        // 副标题 → 一段正文
        if (!outline.subtitle().isBlank()) {
            builder.block(ParagraphBlock.of(outline.subtitle()));
        }

        // 逐章节展开
        for (SummaryOutline.Section section : outline.sections()) {
            if (!section.heading().isBlank()) {
                builder.block(HeadingBlock.of(2, section.heading()));
            }
            for (String para : section.paragraphs()) {
                if (para != null && !para.isBlank()) {
                    builder.block(ParagraphBlock.of(para));
                }
            }
            List<String> bullets = section.bullets().stream()
                    .filter(b -> b != null && !b.isBlank())
                    .toList();
            if (!bullets.isEmpty()) {
                builder.block(ListBlock.unordered(bullets));
            }
        }

        return builder.build();
    }
}
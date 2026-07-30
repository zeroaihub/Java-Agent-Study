package com.zero.ai.agentstudy.day13officeagent.officecore.domain.port;

import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.DocumentFormat;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.DocumentIR;

/**
 * 文档渲染器（DocumentRenderer）——六边形架构的<b>出站端口</b>。
 *
 * <p>它定义了领域对"把 IR 变成某种格式二进制"的诉求，但不关心是用 Apache POI、docx4j 还是
 * PDFBox 实现。领域层依赖这个接口（抽象），而具体实现（{@code WordRenderer}、{@code PdfRenderer}…）
 * 作为适配器放在 adapter 层——这正是依赖倒置：高层策略不依赖低层细节，两者都依赖抽象。</p>
 *
 * <p>因为每种格式一个实现，Pipeline 的渲染阶段可以按 {@link #format()} 选择渲染器，并用
 * Virtual Threads 并行渲染多格式，彼此隔离互不影响。</p>
 *
 * @author zero
 */
public interface DocumentRenderer {

    /**
     * 本渲染器负责的目标格式。
     *
     * @return 目标文档格式
     */
    DocumentFormat format();

    /**
     * 把文档 IR 渲染为目标格式的二进制字节。
     *
     * @param ir 与格式无关的文档中间表示
     * @return 渲染后的文件字节（如 .docx / .pdf 内容）
     */
    byte[] render(DocumentIR ir);

    /**
     * 是否支持渲染给定 IR（可用于能力探测，默认全部支持）。
     *
     * @param ir 文档 IR
  * @return支持返回 {@code true}
     */
    default boolean supports(DocumentIR ir) {
        return ir != null;
    }
}
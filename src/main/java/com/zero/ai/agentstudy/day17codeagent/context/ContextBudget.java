package com.zero.ai.agentstudy.day17codeagent.context;

/**
 * 上下文预算。
 *
 * <h3>为什么必须显式建模「预算」</h3>
 * <p>上下文窗口是 Code Agent 最稀缺的资源。它不是一个数量，而是<b>一个需要被分配给多个竞争者的份额</b>：</p>
 * <ul>
 *     <li>系统提示词与工具清单（固定开销，通常 500~1500 token）</li>
 *     <li>仓库地图 RepoMap</li>
 *     <li>选中文件的原文</li>
 *     <li>历史对话 / 自修复记录</li>
 *     <li><b>留给模型输出的空间</b>（最容易被忘的一项）</li>
 * </ul>
 *
 * <p>忘记预留输出空间是<b>最常见的线上事故</b>：上下文填满到 8192/8192，
 * 模型一个字都吐不出来，表现为「任务莫名其妙失败」。所以本类把
 * {@code reserveForOutput} 做成<b>必填且不可省略</b>。</p>
 *
 * @param total            上下文总窗口（token）
 * @param systemReserve    系统提示词 + 工具清单固定开销
 * @param outputReserve    留给模型生成输出的空间
 * @param historyReserve   留给历史对话 / 自修复轮次的空间
 * @author ZeroAi
 */
public record ContextBudget(
        int total,
        int systemReserve,
        int outputReserve,
        int historyReserve
) {

    /** 常用预设：8k 窗口。 */
    public static ContextBudget of8k() {
        return new ContextBudget(8192, 1200, 1500, 800);
    }

    /** 常用预设：32k 窗口（代码场景的甜点级）。 */
    public static ContextBudget of32k() {
        return new ContextBudget(32768, 2000, 4000, 3000);
    }

    /** 常用预设：128k 窗口（大仓库重构）。 */
    public static ContextBudget of128k() {
        return new ContextBudget(131072, 3000, 8000, 12000);
    }

    public ContextBudget {
        if (total <= 0) {
            throw new IllegalArgumentException("上下文总窗口必须为正数");
        }
        if (outputReserve <= 0) {
            throw new IllegalArgumentException(
                    "必须为模型输出预留空间：不留会让上下文被输入占满，模型一个字都生成不出来");
        }
        int used = systemReserve + outputReserve + historyReserve;
        if (used >= total) {
            throw new IllegalArgumentException(
                    "固定开销 " + used + " 已超出总窗口 " + total + "，没有空间留给代码");
        }
    }

    /** 真正能用来放代码的空间。 */
    public int availableForCode() {
        return total - systemReserve - outputReserve - historyReserve;
    }

    /** 已分配额是否超支。 */
    public boolean isOver(int usedTokens) {
        return usedTokens > availableForCode();
    }

    /** 使用率（0.0 ~ 1.0+）。 */
    public double usage(int usedTokens) {
        return (double) usedTokens / availableForCode();
    }

    @Override
    public String toString() {
        return "ContextBudget{total=" + total
                + ", system=" + systemReserve
                + ", output=" + outputReserve
                + ", history=" + historyReserve
                + ", 可用于代码=" + availableForCode() + "}";
    }
}

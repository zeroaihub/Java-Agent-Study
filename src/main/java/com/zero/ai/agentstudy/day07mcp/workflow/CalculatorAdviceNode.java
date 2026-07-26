package com.zero.ai.agentstudy.day07mcp.workflow;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CalculatorAdviceNode implements WorkflowNode {


    /**
     * 上游天气节点写入上下文的 key
     */
    private final String calculatorResKey;

    /**
     * 本节点建议写回上下文的 key
     */
    private final String adviceKey;

    /**
     * 构造建议节点。
     *
     */
    public CalculatorAdviceNode(String calculatorResKey, String adviceKey) {
        this.calculatorResKey = calculatorResKey;
        this.adviceKey = adviceKey;
    }

    @Override
    public String name() {
        return "calculator-advice";
    }

    @Override
    public boolean execute(WorkflowContext context) {

        String calculatorRes = context.getString(calculatorResKey);
        if (calculatorRes == null || calculatorRes.isEmpty()) {
            context.put("error", "缺少计算结果，无法生成建议");
            return false;
        }
        String[] arr = calculatorRes.split("：");
        String advice;
        if (Double.parseDouble(arr[1]) >= 100) {
            advice = "花费超过了100";
        } else {
            advice = "花费不足100";
        }
        // 3) 把建议写回上下文（作为整条流程的最终产出之一）
        context.put(adviceKey, advice);
        return true;
    }
}

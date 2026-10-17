package com.zero.ai.agentstudy.day17codeagent.generator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 桩（Stub）代码模型：<b>零依赖、可离线、输出确定</b>。
 *
 * <p>它按<b>规则</b>生成补丁——识别需求里的关键词，套用预置的代码模板。
 * 这不是「假装聪明」，而是刻意的工程选择：</p>
 * <ul>
 *     <li><b>可重复</b>：同样输入永远同样输出，教程里的运行截图才对得上；</li>
 *     <li><b>可离线</b>：没有 API Key 也能跑完 Day17 全部 20 章；</li>
 *     <li><b>可测</b>：自修复循环、评测、成本控制这些章节需要稳定的被测对象。</li>
 * </ul>
 *
 * <p><b>必须清醒</b>：它不理解语义，只做模板匹配。真实的能力提升要靠换成
 * {@code SpringAiCodeModel}——但那不影响本模块之外的任何代码。</p>
 *
 * <p>它还会故意在第一次输出里犯错（见 {@link #injectMistake}），
 * 用来驱动 Chapter 13 的自修复循环 ——「能修复错误」是一个 Code Agent 的核心能力，
 * 一个永远不犯错的桩反而让这条链路无从验证。</p>
 *
 * @author ZeroAi
 */
public class StubCodeModel implements CodeModel {

    /** 是否故意在首次回答里注入错误，供自修复循环演示。 */
    private final boolean injectMistake;
    private int callCount;
    private int totalPromptTokens;
    private int totalCompletionTokens;

    public StubCodeModel(boolean injectMistake) {
        this.injectMistake = injectMistake;
    }

    /** 永不犯错的桩。 */
    public static StubCodeModel reliable() {
        return new StubCodeModel(false);
    }

    /** 会在第一次输出里犯错的桩（用于演示自修复）。 */
    public static StubCodeModel flaky() {
        return new StubCodeModel(true);
    }

    @Override
    public String modelId() {
        return "stub-code-model-1.0";
    }

    @Override
    public GenerationOutcome generate(GenerationRequest request) {
        callCount++;
        long start = System.currentTimeMillis();

        String answer;
        Map<String, String> templates = templates();
        String matched = null;
        String template = null;
        for (Map.Entry<String, String> e : templates.entrySet()) {
            if (request.userPrompt().contains(e.getKey())) {
                matched = e.getKey();
                template = e.getValue();
                break;
            }
        }
        if (template == null) {
            answer = "抱歉，我无法从当前上下文中确定应该修改哪个方法。请用 list_files / read_file 先确认目标文件。";
        } else {
            answer = "好的，我来修改 " + matched + "：\n\n" + template;
        }

        // 首次调用时故意少写一个分号 / 用错字段名，制造一次真实的编译错误
        if (injectMistake && callCount == 1) {
            answer = answer.replace("return new Money(this.amount.multiply(BigDecimal.valueOf(factor)));",
                    "return new Money(this.amount.multiply(BigDecimal.valueOf(factor)))");
        }

        int promptTokens = request.estimateInputTokens();
        int completionTokens = (int) Math.ceil(answer.length() * 0.55);
        totalPromptTokens += promptTokens;
        totalCompletionTokens += completionTokens;

        return new GenerationOutcome(answer, promptTokens, completionTokens,
                System.currentTimeMillis() - start, modelId(), false);
    }

    /**
     * 预置模板：关键词 → 补丁文本。
     *
     * <p>注意这些模板里的 SEARCH 片段与 {@code SampleRepository} 的真实内容严格一致，
     * 因此 Demo 里能真正应用成功（而不是「演示一下就算了」）。</p>
     */
    private Map<String, String> templates() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("multiply", """
                File: src/main/java/demo/order/Money.java
                <<<<<<< SEARCH
                    public Money add(Money other) {
                        return new Money(this.amount.add(other.amount));
                    }
                =======
                    public Money add(Money other) {
                        return new Money(this.amount.add(other.amount));
                    }

                    public Money multiply(long factor) {
                        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)));
                    }
                >>>>>>> REPLACE
                """);
        map.put("pay", """
                File: src/main/java/demo/order/OrderService.java
                <<<<<<< SEARCH
                    public boolean isPaid(String orderId) {
                        return repository.findById(orderId)
                                .map(OrderRepository.Order::paid)
                                .orElse(false);
                    }
                =======
                    public boolean isPaid(String orderId) {
                        return repository.findById(orderId)
                                .map(OrderRepository.Order::paid)
                                .orElse(false);
                    }

                    public Money pay(String orderId, boolean vip) {
                        return checkout(orderId, vip);
                    }
                >>>>>>> REPLACE
                """);
        map.put("VIP_THRESHOLD", """
                File: src/main/java/demo/order/DiscountPolicy.java
                <<<<<<< SEARCH
                        if (amountCents >= VIP_THRESHOLD) {
                            return 0.8;
                        }
                        return 0.95;
                =======
                        return amountCents >= VIP_THRESHOLD ? 0.8 : 0.95;
                >>>>>>> REPLACE
                """);
        return map;
    }

    // ---- 统计（评测与成本章节会用到） ----

    public int callCount() {
        return callCount;
    }

    public int totalTokens() {
        return totalPromptTokens + totalCompletionTokens;
    }

    public int totalPromptTokens() {
        return totalPromptTokens;
    }

    public int totalCompletionTokens() {
        return totalCompletionTokens;
    }

    public List<String> knownKeywords() {
        return List.copyOf(new ArrayList<>(templates().keySet()));
    }
}

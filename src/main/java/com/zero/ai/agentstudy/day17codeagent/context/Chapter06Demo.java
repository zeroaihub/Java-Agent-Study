package com.zero.ai.agentstudy.day17codeagent.context;

import com.zero.ai.agentstudy.day17codeagent.repo.SampleRepository;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;

import java.util.Map;

/**
 * Chapter 06 演示：上下文选择与预算裁剪。
 *
 * <p>运行可见：</p>
 * <ol>
 *     <li>同一句话，四种预算下选中结果完全不同（8k 会降级为签名，32k 能放全文）；</li>
 *     <li>依赖联动会把「被影响的文件」一并拉进来；</li>
 *     <li>「不留输出空间」这条常见事故会被直接拦下来。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class Chapter06Demo {

    public static void main(String[] args) {
        VirtualFileSystem vfs = VirtualFileSystem.temp("context-demo");
        vfs.writeAll(SampleRepository.files());
        Map<String, String> sources = vfs.snapshot().files();

        // ---- 1. Token 估算 ----
        System.out.println("=== [1] Token 估算器 ===");
        String english = "public Money checkout(String orderId, boolean vip) {";
        String chinese = "请在下单流程里支持 VIP 折扣";
        System.out.printf("  英文 %2d 字符 → %d token%n", english.length(), TokenEstimator.estimate(english));
        System.out.printf("  中文 %2d 字符 → %d token%n", chinese.length(), TokenEstimator.estimate(chinese));
        System.out.println("  分词：" + TokenEstimator.tokenize("请在 checkout 下单流程里支持 VIP 折扣"));

        ContextSelector selector = new ContextSelector(sources);

        // ---- 2. 大预算：全文入选 ----
        String requirement = "我想给 OrderService 的 checkout 方法加上 VIP 折扣校验，Money 也要支持乘法";
        System.out.println("\n=== [2] 32k 预算：能放全文 ===");
        showSelection(selector, requirement, ContextBudget.of32k());

        // ---- 3. 极小预算：降级 ----
        System.out.println("\n=== [3] 极窄预算（可用于代码仅 380 token）：观察降级行为 ===");
        ContextBudget tiny = new ContextBudget(4096, 3000, 500, 216);
        System.out.println("  " + tiny);
        showSelection(selector, requirement, tiny);

        // ---- 4. 不同需求 → 不同选中 ----
        System.out.println("\n=== [4] 换个需求，选中文件随之改变 ===");
        String other = "DiscountPolicy 的 VIP_THRESHOLD 常量是多少，解释一下阶梯折扣";
        showSelection(selector, other, ContextBudget.of32k());

        // ---- 5. 必选文件 ----
        System.out.println("\n=== [5] 用户显式指定必须包含 README.md ===");
        var forced = selector.select(other, ContextBudget.of32k(),
                java.util.List.of(SampleRepository.README));
        System.out.println("  " + forced);
        forced.items().forEach(i -> System.out.println("    " + i));

        // ---- 6. 忘记预留输出空间 =====
        System.out.println("\n=== [6] 常见事故：不为输出预留空间 ===");
        try {
            new ContextBudget(4096, 2000, 0, 2096);
            System.out.println("  ✗ 竟然通过了");
        } catch (IllegalArgumentException e) {
            System.out.println("  ✓ 被拦截：" + e.getMessage());
        }
        try {
            new ContextBudget(4096, 3000, 2000, 2000);
            System.out.println("  ✗ 竟然通过了");
        } catch (IllegalArgumentException e) {
            System.out.println("  ✓ 被拦截：" + e.getMessage());
        }
    }

    private static void showSelection(ContextSelector selector, String requirement, ContextBudget budget) {
        var result = selector.select(requirement, budget);
        System.out.println("  需求：" + requirement);
        System.out.println("  " + result);
        result.items().forEach(i -> System.out.printf("    %-46s %-10s %5d token  score=%.2f  %s%n",
                shortPath(i.path()), i.mode(), i.tokens(), i.score(), i.reason()));
        if (!result.dropped().isEmpty()) {
            System.out.println("    因预算不足丢弃：" + result.dropped());
        }
        if (!result.exactHits().isEmpty()) {
            System.out.println("    精确符号命中（可用于定位改动点）：");
            result.exactHits().forEach(s -> System.out.println("        " + s));
        }
        System.out.println("    影响面文件：" + result.impactSet());
    }

    private static String shortPath(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }
}

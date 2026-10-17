package com.zero.ai.agentstudy.day17codeagent.patch;

import com.zero.ai.agentstudy.day17codeagent.repo.SampleRepository;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;

/**
 * Chapter 08 演示：从 LLM 的自由文本里解析出补丁。
 *
 * <p>运行可见三种真实世界的脏输入都能被正确处理：</p>
 * <ol>
 *     <li>模型先啰嗦一段，文件行带反引号；</li>
 *     <li>一次性给出两个文件两个补丁；</li>
 *     <li>Unified Diff 格式同样能解析（自动转成 SEARCH/REPLACE 语义）；</li>
 *     <li>完全没有补丁块的输出会被明确判为失败，而不是「假装成功」。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class Chapter08Demo {

    public static void main(String[] args) {
        VirtualFileSystem vfs = VirtualFileSystem.temp("parser-demo");
        vfs.writeAll(SampleRepository.files());
        PatchApplier applier = PatchApplier.strict();

        // ---- 1. 典型脏输入 ----
        String messyOutput = """
                好的，我理解了你的需求。为了实现这个功能，我需要修改两个地方：

                File: `src/main/java/demo/order/Money.java`
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

                这样修改之后，Money 支持乘法，OrderService 多了一个 pay 入口。
                """;

        System.out.println("=== [1] 从含闲聊 / 反引号的脏输出里解析 ===");
        var parsed = PatchParser.parse(messyOutput, null);
        System.out.println("  解析出 " + parsed.patches().size() + " 个补丁：");
        parsed.patches().forEach(p -> System.out.println("    " + p));
        System.out.println("  被忽略的片段数 = " + parsed.rejected().size()
                + "（模型开头的闲聊，正常）");

        System.out.println("\n=== [2] 逐个应用到真实文件 ===");
        int ok = 0;
        for (TextPatch patch : parsed.patches()) {
            PatchResult result = applier.applyTo(vfs, patch);
            System.out.println("  " + result);
            if (result.success()) {
                ok++;
            }
        }
        System.out.println("  成功 " + ok + "/" + parsed.patches().size());

        System.out.println("\n=== [3] Money.java 的审阅 diff ===");
        System.out.println(UnifiedDiff.generateCompact(SampleRepository.MONEY,
                SampleRepository.files().get(SampleRepository.MONEY),
                vfs.read(SampleRepository.MONEY).content()));

        // ---- 2. Unified Diff 格式 ----
        System.out.println("=== [4] Unified Diff 格式也能解析 ===");
        String diffOutput = """
                --- a/src/main/java/demo/order/DiscountPolicy.java
                +++ b/src/main/java/demo/order/DiscountPolicy.java
                @@ -8,7 +8,7 @@
                     private static final long VIP_THRESHOLD = 100_00L;
                 
                     public double discountFor(long amountCents, boolean vip) {
                         if (!vip) {
                             return 1.0;
                         }
                -        if (amountCents >= VIP_THRESHOLD) {
                -            return 0.8;
                -        }
                -        return 0.95;
                +        return amountCents >= VIP_THRESHOLD ? 0.8 : 0.95;
                     }
                """;
        var diffParsed = PatchParser.parse(diffOutput, null);
        diffParsed.patches().forEach(p -> {
            System.out.println("  路径 = " + p.filePath());
            System.out.println("  SEARCH =\n" + indentText(p.search()));
            System.out.println("  REPLACE =\n" + indentText(p.replace()));
            PatchResult r = applier.applyTo(vfs, p);
            System.out.println("  应用 → " + r);
        });

        // ---- 3. 完全没有补丁块 ----
        System.out.println("\n=== [5] 模型只是聊了聊，没有给补丁 ===");
        try {
            PatchParser.parse("好的，我建议你在 OrderService 里加一个 pay 方法即可。", null);
            System.out.println("  ✗ 竟然通过了");
        } catch (RuntimeException e) {
            System.out.println("  ✓ 明确失败：" + e.getMessage());
        }

        // ---- 4. 缺少分隔符的坏补丁 ----
        System.out.println("\n=== [6] SEARCH 块缺少 ======= 分隔符 ===");
        var broken = PatchParser.parse("""
                File: src/main/java/demo/order/Money.java
                <<<<<<< SEARCH
                    public Money add(Money other) {
                >>>>>>> REPLACE

                File: src/main/java/demo/order/Money.java
                <<<<<<< SEARCH
                    public static Money zero() {
                        return new Money(BigDecimal.ZERO);
                    }
                =======
                    public static Money zero() {
                        return Money.zero();
                    }
                >>>>>>> REPLACE
                """, null);
        System.out.println("  成功解析 = " + broken.patches().size() + " 个");
        System.out.println("  被判为无效 = " + broken.rejected());
    }

    private static String indentText(String text) {
        StringBuilder sb = new StringBuilder();
        text.lines().forEach(l -> sb.append("      ").append(l).append('\n'));
        return sb.toString();
    }
}

package com.zero.ai.agentstudy.day17codeagent.review;

import com.zero.ai.agentstudy.day17codeagent.patch.TextPatch;
import com.zero.ai.agentstudy.day17codeagent.patch.PatchApplier;
import com.zero.ai.agentstudy.day17codeagent.patch.PatchResult;
import com.zero.ai.agentstudy.day17codeagent.repo.SampleRepository;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;

/**
 * Chapter 15 演示：规则式代码评审。
 *
 * <p>核心主张：<b>能被规则覆盖的检查，不要交给 LLM。</b></p>
 *
 * @author ZeroAi
 */
public class Chapter15Demo {

    public static void main(String[] args) {
        VirtualFileSystem vfs = VirtualFileSystem.temp("review-demo");
        vfs.writeAll(SampleRepository.files());
        RuleBasedReviewer reviewer = new RuleBasedReviewer();

        System.out.println("=== [0] 规则清单 ===");
        reviewer.ruleNames().forEach(r -> System.out.println("    - " + r));

        // ---- 1. 一个「能编译通过」但问题很多的补丁 ----
        System.out.println("\n=== [1] 审查一个能通过编译但质量糟糕的补丁 ===");
        TextPatch smelly = TextPatch.unique(
                SampleRepository.ORDER_SERVICE,
                "    public boolean isPaid(String orderId) {",
                "    public static long counter = 0;\n\n"
                        + "    public boolean isPaid(String orderId) {\n"
                        + "        System.out.println(\"checking \" + orderId);\n"
                        + "        try {\n"
                        + "            Thread.sleep(3000);\n"
                        + "        } catch (InterruptedException e) {\n"
                        + "        }\n"
                        + "        // TODO 加入缓存\n"
                        + "        return repository.findById(orderId).map(OrderRepository.Order::paid).orElse(false) || counter == 100000;");

        String before = vfs.read(SampleRepository.ORDER_SERVICE).content();
        PatchResult applied = PatchApplier.strict().applyTo(vfs, smelly);
        String after = vfs.read(SampleRepository.ORDER_SERVICE).content();
        System.out.println("  " + applied);

        var comments = reviewer.review(SampleRepository.ORDER_SERVICE, before, after);
        comments.forEach(c -> System.out.println("    " + c));
        var summary = new RuleBasedReviewer.ReviewSummary(comments);
        System.out.println("\n  " + summary);
        System.out.println("  是否存在必须修改的问题 = " + summary.hasBlocker());
        System.out.println("  → 注意：这段补丁完全可以编译通过、测试也可能通过，");
        System.out.println("    但它吞掉了中断异常、使用了公开可变静态字段、留了魔法数字与 TODO。");

        // ---- 2. 只评审增量，不评审存量 ----
        System.out.println("\n=== [2] 为什么只评审增量 ===");
        String untouchedBefore = vfs.read(SampleRepository.DISCOUNT_POLICY).content();
        String untouchedAfter = untouchedBefore;
        var zeroDelta = reviewer.review(SampleRepository.DISCOUNT_POLICY,
                untouchedBefore, untouchedAfter);
        System.out.println("  没有任何改动时产生的意见数 = " + zeroDelta.size());
        System.out.println("  → 评审全量会把历史遗留问题一次性倾泻给 PR 作者，结果就是没人看");

        // ---- 3. 干净的补丁应当零意见 ----
        System.out.println("\n=== [3] 干净的补丁应当零意见 ===");
        String cleanBefore = vfs.read(SampleRepository.MONEY).content();
        PatchApplier.strict().applyTo(vfs, TextPatch.unique(
                SampleRepository.MONEY,
                "    public Money add(Money other) {\n"
                        + "        return new Money(this.amount.add(other.amount));\n"
                        + "    }",
                "    public Money add(Money other) {\n"
                        + "        return new Money(this.amount.add(other.amount));\n"
                        + "    }\n\n"
                        + "    public Money multiply(long factor) {\n"
                        + "        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)));\n"
                        + "    }"));
        String cleanAfter = vfs.read(SampleRepository.MONEY).content();
        var cleanComments = reviewer.review(SampleRepository.MONEY, cleanBefore, cleanAfter);
        System.out.println("  意见条数 = " + cleanComments.size()
                + "（0 才是对的，否则说明规则误报太高）");

        System.out.println("\n=== [4] 小结 ===");
        System.out.println("  规则负责「确定性」部分：格式、反模式、安全红线；");
        System.out.println("  LLM 负责「需要理解语义」的部分：命名是否贴合业务、抽象是否合理。");
        System.out.println("  两者混为一谈的结果就是既贵又不可靠。");
    }
}

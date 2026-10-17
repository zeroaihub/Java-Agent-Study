package com.zero.ai.agentstudy.day17codeagent.repo;

import com.zero.ai.agentstudy.day17codeagent.core.common.CodeException;
import com.zero.ai.agentstudy.day17codeagent.core.model.CodeTaskContext;
import com.zero.ai.agentstudy.day17codeagent.core.model.RepoId;
import com.zero.ai.agentstudy.day17codeagent.core.model.TaskId;
import com.zero.ai.agentstudy.day17codeagent.core.model.TenantId;
import com.zero.ai.agentstudy.day17codeagent.core.model.UserId;

import java.util.Set;

/**
 * Chapter 03 演示：仓库快照与虚拟文件系统（VFS）。
 *
 * <p>运行可见：</p>
 * <ol>
 *     <li>正常读写、列目录都工作；</li>
 *     <li><b>四种越界写法全部被拦截</b>：{@code ../} 穿越、绝对路径、Windows 盘符、
 *         以及字符串前缀「同根名」绕过（{@code work} vs {@code workplace}）；</li>
 *     <li>内容指纹能识别「改了内容」也能识别「没改内容」。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class Chapter03Demo {

    public static void main(String[] args) {
        // ---- 1. 构造任务上下文：后续每一章都从这里出发 ----
        CodeTaskContext ctx = CodeTaskContext.of(
                TenantId.of("tenant-acme"),
                RepoId.of("order-service"),
                UserId.of("zhangsan"),
                Set.of("CODE_WRITER"),
                TaskId.of("task-001"));

        System.out.println("[上下文] " + ctx);
        System.out.println("[沙箱目录] " + ctx.sandboxDirectoryName());

        // ---- 2. 铺一个迷你仓库 ----
        VirtualFileSystem vfs = VirtualFileSystem.temp(ctx.sandboxDirectoryName());
        vfs.writeAll(SampleRepository.files());

        System.out.println("\n=== [1] 仓库结构 ===");
        for (String p : vfs.listFiles("")) {
            System.out.printf("  %-52s %4d 行%n", p, vfs.read(p).lineCount());
        }

        // ---- 3. 读文件 ----
        System.out.println("\n=== [2] 读取 OrderService.java 前 8 行 ===");
        RepoFile orderService = vfs.read(SampleRepository.ORDER_SERVICE);
        System.out.println(orderService.lines(1, 8));

        // ---- 4. 路径越界防护：本节的灵魂 ----
        System.out.println("\n=== [3] 路径越界防护（四种攻击姿势）===");
        String[] escapes = {
                "../../../../etc/passwd",
                "src/../../../secret.txt",
                "/etc/hosts",
                "C:/Windows/System32/config/SAM",
                // 向上 5 级才会真正跑出仓库根——下面那条「向上 4 级」是反例对照
                "src/main/java/./demo/../../../../../evil.java"
        };
        for (String attack : escapes) {
            try {
                vfs.write(attack, "hacked");
                System.out.println("  ✗ 未被拦截：" + attack);
            } catch (CodeException e) {
                System.out.printf("  ✓ 已拦截 [%s] %s%n              → %s%n",
                        e.code(), attack, e.getMessage());
            }
        }

        // 反例对照：「向上 4 级」恰好停在仓库根，是合法路径，不应该被拦截
        String legalButUgly = "src/main/java/./demo/../../../../evil.java";
        System.out.println("  ⓘ 对照 - 这条看起来可疑，但向上 4 级正好停在仓库根内部，合法：");
        vfs.write(legalButUgly, "// 落在仓库根，未越界");
        System.out.printf("     %s → %s%n", legalButUgly, vfs.resolve(legalButUgly));
        System.out.println("     判定不在枪口下的依据是「规范化后的真实落点」，而不是路径里有没有 ..");

        // ---- 5. 「同根名」绕过：字符串 startsWith 的经典陷阱 ----
        System.out.println("\n=== [4] 为什么不能用字符串 startsWith 做根校验 ===");
        PathCheckResult demo = demonstrateRootPrefixTrap(vfs);
        System.out.println("  仓库根         = " + demo.root());
        System.out.println("  越界候选       = " + demo.evilPath());
        System.out.println("  字符串 startsWith 判定 = " + demo.stringStartsWith()
                + "（错误地放行了）");
        System.out.println("  Path.startsWith 判定   = " + demo.pathStartsWith()
                + "（正确地拦截了）");

        // ---- 6. 内容指纹 ----
        System.out.println("\n=== [5] 快照指纹与变更检测 ===");
        RepoSnapshot before = vfs.snapshot();
        System.out.println("  初始指纹 = " + before.fingerprint() + "  文件数 = " + before.size());

        vfs.write(SampleRepository.README, SampleRepository.files().get(SampleRepository.README)
                + "\n## 变更\n- 追加一行\n");
        RepoSnapshot afterEdit = vfs.snapshot();
        System.out.println("  追加一行后指纹 = " + afterEdit.fingerprint()
                + "  sameAs(before) = " + afterEdit.sameAs(before));

        RepoSnapshot.Diff diff = before.diff(afterEdit);
        System.out.println("  差异 = " + diff + "  修改文件 = " + diff.modified().keySet());

        // 写回完全相同的内容 → 指纹应恢复
        vfs.write(SampleRepository.README, SampleRepository.files().get(SampleRepository.README));
        RepoSnapshot reverted = vfs.snapshot();
        System.out.println("  改回原文后 sameAs(before) = " + reverted.sameAs(before)
                + "（内容指纹只看内容，不看修改时间）");

        System.out.println("\n=== 小结 ===");
        System.out.println("  VFS 是所有写操作的唯一入口；路径安全从此不必再散落到每个工具里。");
    }

    /** 演示结构体：字符串前缀判定的陷阱。 */
    public record PathCheckResult(String root, String evilPath,
                                  boolean stringStartsWith, boolean pathStartsWith) {
    }

    /**
     * 复现那个真实踩过的坑：根是 {@code .../vfs/tenant-acme__order-service__task-001}，
     * 攻击路径指向兄弟目录 {@code ...-task-001-evil/secret.txt}。
     * 字符串 {@code startsWith} 会认为它「以根开头」从而放行，而 {@code Path.startsWith} 按路径元素比较，正确拦截。
     */
    private static PathCheckResult demonstrateRootPrefixTrap(VirtualFileSystem vfs) {
        java.nio.file.Path root = vfs.root();
        java.nio.file.Path siblingRoot = root.resolveSibling(root.getFileName() + "-evil");
        java.nio.file.Path evil = siblingRoot.resolve("secret.txt");

        boolean stringCheck = evil.toString().startsWith(root.toString());
        boolean pathCheck = evil.startsWith(root);
        return new PathCheckResult(root.toString(), evil.toString(), stringCheck, pathCheck);
    }
}

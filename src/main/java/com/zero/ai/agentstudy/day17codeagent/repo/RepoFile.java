package com.zero.ai.agentstudy.day17codeagent.repo;

import java.time.Instant;

/**
 * 仓库中的一个文件（不可变快照）。
 *
 * <p>{@code path} 一律是<b>相对于仓库根的 POSIX 风格相对路径</b>（{@code src/main/java/X.java}），
 * 绝不允许出现绝对路径或 {@code ..}。这条约束由 {@link VirtualFileSystem} 在入口强制，
 * 使得下游所有模块都可以放心地「假定路径已经安全」。</p>
 *
 * @param path        仓库内相对路径
 * @param content     文件内容
 * @param sizeBytes   字节数
 * @param readOnly    是否只读（测试金标准文件、CI 配置等不允许 Agent 改）
 * @param modifiedAt  最后修改时间
 * @author ZeroAi
 */
public record RepoFile(
        String path,
        String content,
        int sizeBytes,
        boolean readOnly,
        Instant modifiedAt
) {
    /** 行号数，用于生成带行号的上下文（ referenced by ContextSelector / RepoMap）。 */
    public int lineCount() {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /** 取指定区间行（1-based，闭区间），供引用与补丁预览使用。 */
    public String lines(int fromInclusive, int toInclusive) {
        String[] all = content == null ? new String[0] : content.split("\n", -1);
        int from = Math.max(1, fromInclusive);
        int to = Math.min(all.length, toInclusive);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append(all[i - 1]);
            if (i < to) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}

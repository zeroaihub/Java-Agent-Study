package com.zero.ai.agentstudy.day17codeagent.gitops;

import com.zero.ai.agentstudy.day17codeagent.patch.UnifiedDiff;
import com.zero.ai.agentstudy.day17codeagent.repo.RepoSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 变更集：一次 Agent 任务改了哪些文件、各改了多少。
 *
 * <h3>为什么 Agent 必须产出变更集</h3>
 * <p>Agent 跑完一次任务，人类需要回答三个问题：<b>改了什么 / 影响多大 / 能不能信</b>。
 * 这三个问题的答案，都必须能从变更集里算出来，而不是靠读 diff 猜。</p>
 *
 * <p>这里有两条硬经验：</p>
 * <ol>
 *     <li><b>只统计「AKA 内容真的变了的」文件</b>：只看修改时间会把「touch 一下」误判为改动，
 *         于是 PR 里躺着一堆零行变更的文件；</li>
 *     <li><b>风险等级要能算出来</b>：核心目录 + 大改动 = 需要重点评审，
 *         这条规则把「PR 该找谁审」从拍脑袋变成可自动化。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class ChangeBundle {

    private final List<FileChange> changes;

    private ChangeBundle(List<FileChange> changes) {
        this.changes = List.copyOf(changes);
    }

    /** 由改动前后的快照生成变更集。 */
    public static ChangeBundle from(RepoSnapshot before, RepoSnapshot after) {
        List<FileChange> changes = new ArrayList<>();
        RepoSnapshot.Diff diff = before.diff(after);

        diff.added().forEach((path, content) -> changes.add(new FileChange(
                FileChange.ChangeType.ADDED, path, lineCount(content), 0, "新增文件")));

        diff.modified().forEach((path, content) -> {
            String oldContent = before.get(path);
            Map<String, Integer> stat = UnifiedDiff.stat(oldContent, content);
            changes.add(new FileChange(
                    FileChange.ChangeType.MODIFIED, path,
                    stat.get("added"), stat.get("removed"), "内容变更"));
        });

        diff.removed().forEach((path, content) -> changes.add(new FileChange(
                FileChange.ChangeType.DELETED, path, 0, lineCount(content), "删除文件")));

        // 排序：风险高的排前面，评审时先看到它们
        changes.sort((a, b) -> Integer.compare(
                b.risk().ordinal(), a.risk().ordinal()));
        return new ChangeBundle(changes);
    }

    private static int lineCount(String content) {
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

    public List<FileChange> changes() {
        return changes;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public int fileCount() {
        return changes.size();
    }

    public int totalAdded() {
        return changes.stream().mapToInt(FileChange::addedLines).sum();
    }

    public int totalRemoved() {
        return changes.stream().mapToInt(FileChange::removedLines).sum();
    }

    /** 整体风险取各文件的最大值。 */
    public FileChange.RiskLevel overallRisk() {
        return changes.stream()
                .map(FileChange::risk)
                .max(Enum::compareTo)
                .orElse(FileChange.RiskLevel.NONE);
    }

    public List<String> paths() {
        return changes.stream().map(FileChange::path).toList();
    }

    /** 是否触及核心源码（决定要不要拉业务负责人评审）。 */
    public boolean touchesCoreSource() {
        return changes.stream()
                .anyMatch(c -> c.path().contains("src/main/java")
                        && c.changeType() != FileChange.ChangeType.DELETED);
    }

    @Override
    public String toString() {
        return String.format("ChangeBundle{文件 %d 个, +%d -%d, 整体风险=%s}",
                fileCount(), totalAdded(), totalRemoved(), overallRisk().display());
    }
}

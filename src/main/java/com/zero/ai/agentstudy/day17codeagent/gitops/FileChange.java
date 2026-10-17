package com.zero.ai.agentstudy.day17codeagent.gitops;

/**
 * 一次文件级变更。
 *
 * @param changeType 变更类型
 * @param path       仓库内相对路径
 * @param addedLines 新增行数
 * @param removedLines 删除行数
 * @param note       变更说明
 * @author ZeroAi
 */
public record FileChange(
        ChangeType changeType,
        String path,
        int addedLines,
        int removedLines,
        String note
) {

    public enum ChangeType {
        ADDED("新增"), MODIFIED("修改"), DELETED("删除"), RENAMED("重命名");

        private final String display;

        ChangeType(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }

    public int changedLines() {
        return addedLines + removedLines;
    }

    /** 判定风险等级：大改动 + 核心路径 = 高风险。 */
    public RiskLevel risk() {
        int scale = changedLines();
        boolean core = path.contains("src/main/java") && !path.contains("test");
        if (scale == 0) {
            return RiskLevel.NONE;
        }
        if (core && scale >= 60) {
            return RiskLevel.HIGH;
        }
        if (scale >= 20 || core) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    public enum RiskLevel {
        NONE("无"), LOW("低"), MEDIUM("中"), HIGH("高");

        private final String display;

        RiskLevel(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }

    @Override
    public String toString() {
        return String.format("%-4s %-52s +%-4d -%-4d [%s]",
                changeType.display(), path, addedLines, removedLines, risk().display());
    }
}

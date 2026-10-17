package com.zero.ai.agentstudy.day17codeagent.review;

/**
 * 一条代码评审意见。
 *
 * <p>与编译诊断的区别：诊断是「编译器说你错」，评审是「工程规范说你不妥」。
 * 二者都不可能靠 LLM 保证，必须由确定性规则给出。<b>规则能给的部分不要交给模型</b>——
 * 模型评审会漏、会奉承、会随 temperature 抖动。</p>
 *
 * @param severity  严重程度
 * @param rule      命中的规则名
 * @param file      文件
 * @param line      行号
 * @param message   问题描述
 * @param suggestion 修改建议
 * @author ZeroAi
 */
public record ReviewComment(
        Severity severity,
        String rule,
        String file,
        int line,
        String message,
        String suggestion
) {

    public enum Severity {
        /** 必须改。 */
        BLOCKER("必须修改"),
        /** 强烈建议改。 */
        MAJOR("建议修改"),
        /** 可以讨论。 */
        MINOR("可选优化"),
        /** 仅是赞许 / 提示。 */
        INFO("提示");

        private final String display;

        Severity(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }

    public boolean isBlocking() {
        return severity == Severity.BLOCKER;
    }

    @Override
    public String toString() {
        return String.format("[%s/%s] %s:%d %s｜建议：%s",
                severity.display(), rule, file, line, message, suggestion);
    }
}

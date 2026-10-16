package com.zero.ai.agentstudy.day15knowledgeplatform.acl.model;

/**
 * 权限动作。
 *
 * <p><b>为什么把权限枚举成类型，而不是用字符串？</b>
 * 字符串权限（{@code "kb:read"}）的问题和裸 String ID 一样：
 * 拼错了不会报错，只会得到一个永远匹配不上的权限——
 * <b>表现为"莫名其妙没权限"，而且极难排查</b>。
 * 枚举让它在编译期就被发现。</p>
 *
 * @author ZeroAi
 */
public enum Permission {

    /** 检索知识库内容。 */
    SEARCH,
    /** 上传/更新文档。 */
    WRITE,
    /** 删除文档。 */
    DELETE,
    /** 管理知识库配置与成员。 */
    MANAGE,
    /** 查看审计日志与统计。 */
    AUDIT;

    /**
     * 该权限所需的最低角色。
     *
     * <p>把「权限 → 角色」的映射放在枚举里，
     * 而不是散落在各个 if 判断中，是为了让<b>授权规则只有一处定义</b>。
     * 散落的后果是：改了 A 处的判断忘了改 B 处，
     * 于是出现"界面上显示有权限、点了却报错"的经典不一致。</p>
     */
    public Role minimumRole() {
        return switch (this) {
            case SEARCH -> Role.READER;
            case WRITE -> Role.CONTRIBUTOR;
            case DELETE -> Role.CONTRIBUTOR;
            case MANAGE -> Role.MANAGER;
            case AUDIT -> Role.MANAGER;
        };
    }
}

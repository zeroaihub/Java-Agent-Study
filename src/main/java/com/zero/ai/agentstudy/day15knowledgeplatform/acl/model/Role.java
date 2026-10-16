package com.zero.ai.agentstudy.day15knowledgeplatform.acl.model;

/**
 * 角色。
 *
 * <p>权限体系的分层：<b>角色 → 权限 → 资源</b>。
 * 角色是权限的集合，用户通过被赋予角色而获得权限。</p>
 *
 * <p><b>为什么要角色这一层，而不直接给用户授权？</b>
 * 因为企业里人员的流动远快于岗位的变动。
 * 直接给 500 个人各自授权，人员一变动就要改 500 处，而且必然改漏；
 * 用角色则只需改一次"张三从研发空间移到法务空间"。
 * <b>角色是对"岗位"的建模，而岗位比人稳定得多。</b></p>
 *
 * <p>权限大小按枚举声明顺序递增，用于快速比较。</p>
 *
 * @author ZeroAi
 */
public enum Role {

    /** 无任何权限（默认拒绝）。 */
    NONE(0),
    /** 可读：能检索到该知识库的内容。 */
    READER(10),
    /** 可写：能上传、删除文档。 */
    CONTRIBUTOR(20),
    /** 可管：能管理文档与元数据，但不能改权限。 */
    MANAGER(30),
    /** 管理员：含改权限、删库。 */
    ADMIN(40);

    private final int level;

    Role(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    /**
     * 当前角色是否不低于目标角色。
     */
    public boolean implies(Role other) {
        return other == null || this.level >= other.level;
    }
}

# 第 10 章：knowledge-acl —— 权限感知检索

> 配套代码：`acl/`
> 可运行 Demo：`demo/Chapter09Demo.java`（第 10 章部分）

---

## 一、这一章要解决的问题

这是全 Day15 **最容易出事故、后果也最严重**的一章。

设想一个场景：

> 某公司用知识平台做全员问答。
> 法务空间里有一份《核心团队薪酬调整方案》，标记为机密。
> 某天，一个销售岗位的同事随口问了一句：「今年薪酬调整幅度是多少？」
> **AI 把 18% 这个数字原原本本地告诉了他。**

这就是**权限泄漏**。它不是"功能不完善"，而是：

> **一次可以上新闻的合规事故。**

本章要交付的能力只有一个，但它决定了整个平台能不能商用：

```
查询 → ① 权限收敛出「可读知识库集合」 → ② 只在可读范围内检索 → 结果
```

**关键在 ① 和 ② 的顺序：权限必须在检索之前生效。**

---

## 二、核心原则：默认拒绝（Deny by Default）

```java
public record AccessControlList(TenantId tenantId,
                                KnowledgeBaseId knowledgeBaseId,
                                Map<String, Role> userRoles,
                                Map<String, Role> groupRoles,
                                Role publicRole) {
```

**不在 ACL 里的主体，一律无权访问。**

与之相对的是「默认允许 + 黑名单」，那是安全设计的反面教材——
**只要有一处漏配，机密就泄漏了**，而漏配是必然会发生的事。

> **默认拒绝保证了：出错的后果是"用户抱怨没权限"，而不是"机密泄漏"。**
> 前者是服务可用性问题，后者是要上新闻的合规事故。
> 这两者的代价差了好几个数量级。

---

## 三、核心设计一：角色 + 权限的两层映射

```
用户/组 → Role（角色）→ Permission（权限）→ 资源
```

### 3.1 为什么要有 Role 这一层

因为**企业里人员的流动远快于岗位的变动**。

直接给 500 个人各自授权，人员一变动就要改 500 处，而且必然改漏；
用角色则只需改一次"张三从研发空间移到法务空间"。

> **角色是对「岗位」的建模，而岗位比人稳定得多。**

### 3.2 权限到角色的映射必须集中定义

```java
public enum Permission {
    SEARCH, WRITE, DELETE, MANAGE, AUDIT;

    public Role minimumRole() {
        return switch (this) {
            case SEARCH -> Role.READER;
            case WRITE  -> Role.CONTRIBUTOR;
            case DELETE -> Role.CONTRIBUTOR;
            case MANAGE -> Role.MANAGER;
            case AUDIT  -> Role.MANAGER;
        };
    }
}
```

把「权限 → 角色」的映射放在枚举里，而不是散落在各个 `if` 判断中，
是为了让**授权规则只有一处定义**。

散落的后果是：改了 A 处的判断忘了改 B 处，
于是出现"界面上显示有权限、点了却报错"的经典不一致。

### 3.3 有效角色取"最高"，不是"第一个匹配"

```java
public Role effectiveRole(Principal principal) {
    if (!this.tenantId.equals(principal.tenantId())) {
        return Role.NONE;          // ← 跨租户一律无权
    }
    Role best = publicRole;
    // 用户角色
    Role byUser = userRoles.get(principal.userId());
    if (byUser != null && byUser.level() > best.level()) best = byUser;
    // 组角色
    for (String group : principal.groups()) { ... }
    return best;
}
```

一个人可能同时通过多条途径获得授权：他个人被授予 READER，
但他所在的"法务组"被授予 ADMIN。此时有效权限应该是 ADMIN。

> 取最高角色符合直觉，也符合绝大多数企业的授权实践；
> 反过来的「取最低」会导致"加了个组，反而权限变少了"的诡异现象。

---

## 四、核心设计二：跨租户校验必须放在最前面

```java
if (!this.tenantId.equals(principal.tenantId())) {
    return Role.NONE;
}
```

这段代码是全 Day15 最重要的一行之一。

**漏掉它，攻击者用 A 公司的账号去访问 ID 恰好相同的 B 公司资源，就能成功。**
这是多租户系统里最常见的一类越权漏洞，它的名字叫
**IDOR（Insecure Direct Object Reference）**，常年挂在 OWASP Top 10 里。

注意这里**返回 NONE 而不是抛异常**：
因为 `effectiveRole` 是**判定**而非**断言**。
判定函数应该"回答有没有权限"，
这样它在检索过滤（对大量知识库逐一判定）时也能安全使用。

Demo 实测（eve 属于 globex 租户，组名也叫 legal）：

```
alice    合同库 SEARCH=false WRITE=false | 制度库 SEARCH=true  WRITE=true
bob      合同库 SEARCH=true  WRITE=true  | 制度库 SEARCH=true  WRITE=false
carol    合同库 SEARCH=false WRITE=false | 制度库 SEARCH=true  WRITE=false
eve      合同库 SEARCH=false WRITE=false | 制度库 SEARCH=false WRITE=false
```

> **eve 这一行是最关键的**：她属于 globex 租户，
> 即便组名也叫 `legal`，也拿不到 acme 的任何权限。

---

## 五、核心设计三：权限感知检索（本模块最重要的方法）

```java
public Set<KnowledgeBaseId> resolveReadableScope(Principal principal,
                                                 Set<KnowledgeBaseId> candidateKbIds) {
    if (candidateKbIds == null || candidateKbIds.isEmpty()) {
        // 不限知识库：遍历该租户下全部 ACL，挑出可读的
        for (AccessControlList acl : aclRepository.findByTenant(tenantId)) {
            if (acl.hasPermission(principal, Permission.SEARCH)) {
                readable.add(acl.knowledgeBaseId());
            }
        }
        return readable;
    }
    // 限定范围：逐一鉴权，只保留有 SEARCH 权限的
    ...
}
```

**这个方法返回的不是「全部知识库」，而是「全部可读的知识库」。**
这正是"权限前置"的体现。

### 5.1 为什么权限过滤必须前置到召回之前

这个结论来自 Confluence AI 的设计启示，也是 Glean 的核心竞争力所在。

**因为向量检索（尤其是 HNSW 这类近似索引）返回的是「全局最近的 K 条」。**

假设用户只有 1 个知识库的权限，而全库有 100 个，
那么全局 Top-50 里可能只有 0~1 条是他有权看的。

**后置过滤的后果是灾难性的三重损失：**

1. **召回率凭空归零**——正确的那条排在第 51 名，永远拿不到；
2. **安全边界被突破**——无权数据已经被读进应用内存，
   日志、异常栈、序列化缓存任何一处疏忽都会泄漏；
3. **性能浪费**——算了 50 条相似度，扔掉 49 条。

### 5.2 为什么无权时返回空集合而不是抛异常

因为「没有权限看到任何东西」是一个**正常的业务结果**：查不到任何东西。

抛异常会让它在检索链路里变成一次 500 错误，
从而触发无意义的重试与告警。
上层可以在需要时把"空结果"包装成友好的提示。

---

## 六、核心设计四：职责分离（Separation of Duties）

```java
public AccessControlList grant(Principal operator, KnowledgeBaseId kbId,
                               String targetUser, String targetGroup, Role role) {
    requirePermission(operator, kbId, Permission.MANAGE);   // ← 必须有 MANAGE 权限
    ...
}
```

**能创建知识库的人，不应该能给自己授权。**

否则任何有创建权限的人都能通过「创建 → 自授权」
把权限体系架空：建一个库 → 给自己 ADMIN → 读别人的机密。

**现实中的对应措施是**：创建资源走业务系统，授权走独立的审批流程。

这也解释了为什么第 13 章的 `KnowledgeWorkspaceController`
（创建空间/知识库）与第 14 章的 `AdminController`（授权）
要**严格分离**、由不同的权限门槛保护。

Demo 实测：

```
✓ 已拦截：主体 carol 无权对知识库 kb-b6b27b3d… 执行 MANAGE
```

---

## 七、核心设计五：授权必须留痕

```java
return KnowledgeResult.ok(Map.of(
        "operator", operator.userId(),
        "knowledgeBaseId", knowledgeBaseId,
        "grantedTo", "user:" + userId,
        "role", parsedRole.name(),
        "aclSnapshot", updated.toString()));
```

每一次授权都记录：**谁（operator）→ 对谁（target）→ 授予什么（role）**。

> **没有审计日志的权限系统，在出现泄漏事故时无法追溯——
> 你既不知道是谁开的口子，也不知道开给了谁、什么时候开的。**

生产环境应写入**独立的审计表且不可修改**（append-only）。

---

## 八、运行 Demo

```bash
java -cp target/classes:<依赖> com.zero.ai.agentstudy.day15knowledgeplatform.demo.Chapter09Demo
```

第 10 章部分真实输出：

```
第 10 章 [2] 权限感知检索：把「能读哪些库」下推给检索层
  alice    可读知识库：[kb-e9ef91a0…]
  carol    可读知识库：[kb-e9ef91a0…]
  eve      可读知识库：[]          ← 另一个租户，什么都看不到

第 10 章 [3] 职责分离：创建者不能给自己授权
  ✓ 已拦截：主体 carol 无权对知识库 kb-b6b27b3d… 执行 MANAGE
```

---

## 九、避坑指南

| 坑 | 后果 | 对策 |
| --- | --- | --- |
| 默认允许 + 黑名单 | 一处漏配即泄漏 | 默认拒绝（Deny by Default） |
| 权限过滤后置到召回之后 | 召回率归零 + 无权数据进内存 | `resolveReadableScope` 前置收敛 |
| ACL 判定不校验租户 | IDOR 越权漏洞（OWASP Top 10） | `effectiveRole` 第一行就比对租户 |
| 创建者能给自己授权 | 权限体系被彻底架空 | 职责分离，授权走独立入口 |
| 授权无审计 | 出事故无法追溯 | 记录操作者/目标/角色，写入 append-only 审计表 |
| 用字符串表示权限 | 拼错不报错，表现为"莫名其妙没权限" | 用枚举，编译期发现 |
| 有效权限取"第一个匹配" | 加了组反而权限变少 | 取最高角色 |
| 按租户过滤时"取出全部再过滤" | 百万知识库规模下 OOM | 仓储提供 `findByTenant`，由 SQL WHERE 完成 |

---

## 十、面试题

**Q1：为什么权限过滤必须前置到召回之前，而不是召回后再过滤？**

> 三个原因：
> 1. **召回率归零**——近似索引（HNSW）返回的是全局 Top-K。
>    如果用户只有 1/100 的权限，全局 Top-50 过滤后可能一条不剩，
>    而真正正确的结果排在第 51 名，永远拿不回来；
> 2. **安全**——无权数据已经被读进应用内存，
>    日志、异常栈、序列化缓存任何一处疏忽都会造成泄漏；
> 3. **性能**——算了 50 条相似度，扔掉 49 条。
>
> 正确做法是：先算出「这个主体能读哪些知识库」，
> 把这个集合**作为过滤条件下推给检索层**，让检索只在有权限的集合里找。

**Q2：多租户系统里最常见的越权漏洞是什么？怎么防？**

> **IDOR（Insecure Direct Object Reference）**——
> 攻击者用 A 公司的账号去访问 ID 恰好相同的 B 公司资源。
>
> 防御要**分层**（纵深防御）：
> 1. **ACL 判定层**：判定的第一件事就是比对主体租户与资源租户；
> 2. **仓储层**：用 `tenantId:entityId` 复合键，
>    让跨租户的查询在存储层就查不到；
> 3. **租户来源**：租户只能从服务端可验证的信息（JWT/OAuth2）推断，
>    绝不接受客户端指定。
>
> 分层的好处是：**单点被攻破不足以突破整个防线。**

**Q3：为什么要「职责分离」？创建者不能给自己授权吗？**

> 不能。如果创建者自动成为管理员，
> 那么任何有"创建知识库"权限的人，
> 都能给自己授予"查看机密库"的权限——**权限体系就此失效**。
>
> 正确做法是：创建资源与授予权限是**两个独立的动作**，
> 由不同的权限门槛保护，最好还有独立的审批流程。
> 这也是为什么本项目的"空间/知识库管理"与"授权管理"
> 分在两个 Controller 里。

**Q4：授权操作为什么必须留痕？**

> 因为没有审计日志的权限系统，在出现泄漏事故时**无法追溯**：
> 你既不知道是谁开的口子，也不知道开给了谁、什么时候开的，
> 更不知道还有哪些口子开着。
>
> 生产上应写入**独立的、append-only 的审计表**，
> 记录：操作者、目标（用户/组）、授予的角色、时间、操作前后 ACL 快照。

---

## 十一、本章小结

- **默认拒绝**是权限设计的第一原则，出错的代价决定了这个选择。
- **角色是对岗位的建模**，权限到角色的映射必须集中定义。
- **跨租户校验是第一行代码**，它防的是 OWASP Top 10 里的 IDOR。
- **权限必须前置到召回之前**，后置过滤会造成召回率、安全、性能三重损失。
- **职责分离**：能创建的人不能给自己授权。
- **授权必须留痕**，否则事故无法追溯。

下一章，我们处理知识的**时效性问题**：
数据源变了怎么办？删了怎么办？

---

> **第 10 章结束。** 输入「继续」，进入 **第 11 章：knowledge-sync —— 增量同步与删除同步**。

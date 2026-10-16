# 第 9 章：knowledge-workspace —— 租户、工作空间与知识库

> 配套代码：`workspace/`
> 可运行 Demo：`demo/Chapter09Demo.java`（第 9 章部分）

---

## 一、这一章要解决的问题

前面 8 章我们一直在处理"文档"。但企业里的知识**从来不是一堆平铺的文档**——
它是有归属、有边界、有组织的。

看一个真实场景：

> 一家公司有 5000 名员工、10 万篇文档。
> 财务部的报销制度、法务部的合同模板、研发部的 API 文档、
> 客服部的应对话术——**它们混在一起吗？**

显然不能。它们必须**分区**，而且**谁能看哪个区**要有明确规则。

本章要交付的，就是这个"分区"的结构：

```
Tenant（企业）
  └── Workspace（研发空间 / 法务空间 / 财务空间 …）
        └── KnowledgeBase（API 文档库 / 合同库 …）  ← 检索作用域，受 ACL 保护
              └── Document（一份 PDF/Word …，有 Version）
                    └── Chunk（切片，带 Metadata）
```

---

## 二、三层结构各自的职责

### 2.1 Tenant（租户）—— 一家使用平台的企业

SaaS 的顶层隔离单位。**所有数据都必须带 `tenant_id`**，
租户之间物理/逻辑隔离，绝不串数据。

### 2.2 Workspace（工作空间）—— 租户内部的分区

**为什么需要这一层？因为「租户」的粒度太粗了。**

一家公司内部，研发、法务、财务、客服各自的知识是完全不同的世界——
它们不该互相出现在对方的检索结果里，哪怕同属一家公司。

这就是 Confluence 的 Space、Notion 的 Teamspace 存在的原因。

> **知识天然是「分域」的，而 Workspace 就是对「域」的建模。**

**一条重要的设计经验**：Workspace 是**权限的默认边界**。
授权时绝大多数情况是「把某人加入某个空间」，
而不是「把某人加入某篇文档」。文档级细粒度授权只在例外场景下才用
（比如一份仅限 3 人可见的并购合同）。

> **先做好空间级授权，再按需支持文档级。**
> 反过来做会让权限体系复杂到没人敢维护。

### 2.3 KnowledgeBase（知识库）—— 检索的作用域

**它是最容易被低估的一个概念。**

很多人做 RAG 时会跳过「知识库」这层，直接让所有文档躺在一个大池子里，
理由是"反正都能搜到"。这在企业环境里会立刻出问题：

| 问题 | 说明 |
| --- | --- |
| **无法做权限** | 权限必须挂在某个实体上。没有这层就只能挂到文档级，100 万篇文档 = 100 万条 ACL，检索时逐条判断，性能崩溃 |
| **无法做配额与计费** | SaaS 按"文档量/知识库数"收费，没有这层就没法计价 |
| **无法隔离质量** | 产品文档和聊天记录混在一起，检索质量被低质量数据拖垮，而你可能根本不知道是哪些数据在拖后腿 |
| **无法按场景调优** | 技术文档适合小切片 + 结构化策略，FAQ 适合整段切片。混在一起只能取一个都不合适的折中参数 |

> **所以：知识库不是「分组文件夹」，而是检索、权限、计费、调优的共同边界。**
> 这也是为什么第 7 章的 `Chunk` 要**冗余存储 `knowledgeBaseId`**——
> 它是检索作用域下推的关键。

---

## 三、核心设计一：租户只从上下文取，绝不由调用方传入

```java
public class WorkspaceService {
    public Workspace createWorkspace(String name, String description, Metadata metadata) {
        TenantId tenantId = TenantContext.require();   // ← 从上下文取
        return workspaceRepository.save(Workspace.create(tenantId, name, description, metadata));
    }
}
```

**请对比两种签名：**

```java
// 危险：调用方可以传任意 tenantId
KnowledgeBase create(TenantId tenantId, String name)

// 安全：租户只能由上下文决定
KnowledgeBase create(String name)
```

第一种签名意味着：任何一个 Controller 方法只要把请求参数里的 tenantId
透传进来，就构成了一个越权漏洞。
**而这类漏洞在代码审查时极难发现——因为方法签名看起来"很合理"。**

> **把租户从参数列表里彻底移除，是从根源上消灭这类漏洞。**

---

## 四、核心设计二：复合键让跨租户访问在存储层就不可能发生

```java
private static String key(TenantId tenantId, WorkspaceId workspaceId) {
    return tenantId.value() + ":" + workspaceId.value();
}
```

这不是多此一举。它让「跨租户访问」在**存储层就不可能发生**：

即使上层代码漏了租户校验，用 A 租户的上下文去查 B 租户的空间 ID，
拼出来的 key 在表里**根本不存在**。

Demo 实测：

```
在 acme 上下文下取到工作空间：ws-8da4714f-1e36-4207-ac95-e99e6b1f7a53
✓ 已拦截跨租户访问：工作空间不存在：ws-8da4714f-1e36-4207-ac95-e99e6b1f7a53
```

> **把安全约束下沉到数据结构，比依赖开发者记得写过滤条件可靠得多。**

这一点在第 10 章的 ACL 仓储里同样适用。

---

## 五、核心设计三：知识库记录当前 Embedding 模型

```java
public void recordEmbedding(String model, int writtenCount) {
    if (this.embeddingModel != null && !this.embeddingModel.equals(model)) {
        throw new KnowledgeException(KnowledgeErrorCode.EMBEDDING_FAILED,
                "知识库 " + id + " 已使用模型 " + this.embeddingModel
                        + "，无法写入 " + model + " 产生的向量。"
                        + "更换 Embedding 模型必须全量重建向量（先建新库 → 双写 → 验证 → 切换）");
    }
    this.embeddingModel = model;
    this.documentCount += writtenCount;
}
```

**这是「Embedding 模型锁版本」这条铁律在库级别的落地位置。**

库级记录当前模型，每次写入前比对：不一致就**拒绝写入**，
而不是让新旧向量悄悄混在一起。

这个字段还带来一个重要的运维能力：

> **更换模型的安全姿势**：新建一个库 → 双写 → 对比验证 → 切换 → 下线旧库。
> 有了 `embeddingModel` 字段，你随时能查到"这个库用的是哪套向量"，
> 而不是靠翻代码或猜。

---

## 六、核心设计四：归档而非删除

```java
public void archive() { ... }
public void restore() { ... }
```

**为什么工作空间是「归档」而不是「删除」？**

因为知识是有价值的资产，直接删除等于销毁资产。
归档后空间不再出现在默认检索范围内，但数据仍在，需要时可以恢复。

这与文档的 `DELETED` 状态形成对比：

- **文档删除**往往是为了合规（数据必须真的消失）；
- **空间归档**通常只是"这个部门不用了"。

> **两种场景的诉求相反，所以建模必须不同。**
> 混为一谈会导致：要么该删的没删干净（合规风险），
> 要么不该删的被删了（资产损失）。

---

## 七、运行 Demo

```bash
java -cp target/classes:<依赖> com.zero.ai.agentstudy.day15knowledgeplatform.demo.Chapter09Demo
```

真实输出（第 9 章部分）：

```
第 9 章 [1] 租户 → 工作空间 → 知识库 三层结构
  租户 acme 下创建工作空间：
    - Workspace{ws-8da4714f…, name='财务空间', archived=false}
    - Workspace{ws-ca99daa7…, name='法务空间', archived=false}
  在财务空间下创建知识库：KnowledgeBase{kb-e9ef91a0…, name='差旅制度库', model=null, docs=0}
  在法务空间下创建知识库：KnowledgeBase{kb-b6b27b3d…, name='合同库', model=null, docs=0}

第 9 章 [2] 跨租户访问：在存储层就被挡住
  ✓ 已拦截跨租户访问：工作空间不存在：ws-8da4714f…
```

---

## 八、避坑指南

| 坑 | 后果 | 对策 |
| --- | --- | --- |
| 方法签名接受 tenantId 参数 | 越权漏洞，且代码审查极难发现 | 租户只从 `TenantContext` 取 |
| 仓储只用业务 ID 做 key | 跨租户访问可能成功 | 用 `tenantId:entityId` 复合键 |
| 跳过知识库这层 | 无法做权限、计费、质量隔离、场景调优 | 知识库作为检索作用域的边界 |
| 工作空间直接删除 | 销毁知识资产 | 归档 + 可恢复 |
| 知识库不记录 Embedding 模型 | 换模型时无法确认哪些库需要重建 | `recordEmbedding` 强制校验 |
| 忘记清理 TenantContext | 线程复用导致租户串号 | Filter 的 `finally` 中 clear |

---

## 九、面试题

**Q1：多租户系统里，租户 ID 应该怎么传递？**

> **绝不能作为方法参数由调用方传递。**
> 一旦某个 Controller 把请求参数里的 tenantId 透传进来，
> 就是一个越权漏洞，而且这类漏洞代码审查时很难发现——
> 因为签名看起来"很合理"。
>
> 正确做法：在请求入口（Filter）从**服务端可验证的信息**
> （JWT claims、OAuth2 绑定关系、API Key 对应的服务端记录）解析出租户，
> 放入 `TenantContext`（ThreadLocal），业务代码从上下文取。
>
> 两个配套要求：
> 1. **请求结束必须 clear**——容器线程复用，不清理会导致租户串号；
> 2. **仓储用复合键** `tenantId:entityId`，
>    让跨租户访问在存储层就不可能发生。

**Q2：为什么需要「工作空间」这一层，有租户不够吗？**

> 租户粒度太粗。一家公司内部，研发、法务、财务、客服的知识是完全不同的世界，
> 不该互相出现在对方的检索结果里，哪怕同属一家公司。
>
> 而且 Workspace 是**权限的默认边界**——绝大多数授权是"把某人加入某个空间"，
> 而不是"把某人加入某篇文档"。
> 文档级细粒度授权只在例外场景才用。
>
> **先做好空间级授权，再按需支持文档级。** 反过来做会让权限体系复杂到没人敢维护。

**Q3：知识库这一层能带来什么？不就是个文件夹吗？**

> 它是**检索、权限、计费、调优的共同边界**，远不止分组：
> - **权限**：权限必须挂在某个实体上。没有这层就只能挂到文档级，
>   100 万篇文档 = 100 万条 ACL，检索时逐条判断，性能崩溃；
> - **计费**：SaaS 按"文档量/知识库数"收费；
> - **质量隔离**：产品文档和聊天记录混在一起，
>   检索质量被低质量数据拖垮，而你不知道是谁在拖后腿；
> - **场景调优**：技术文档适合小切片 + 结构化策略，FAQ 适合整段切片，
>   混在一起只能取一个都不合适的折中参数。

---

## 十、本章小结

- **三层结构**：Tenant（租户隔离）→ Workspace（知识分域）→ KnowledgeBase（检索作用域）。
- **租户只从上下文取**，把越权漏洞从根源上消灭。
- **仓储用复合键**，让跨租户访问在存储层就不可能发生。
- **知识库记录 Embedding 模型**，这是「模型锁版本」在库级别的落地。
- **工作空间用归档而非删除**，因为知识是资产。

下一章，我们给这三层结构加上**权限**——
这决定了谁能看到什么，也是企业知识平台最容易出事故的地方。

---

> **第 9 章结束。** 输入「继续」，进入 **第 10 章：knowledge-acl —— 权限感知检索**。

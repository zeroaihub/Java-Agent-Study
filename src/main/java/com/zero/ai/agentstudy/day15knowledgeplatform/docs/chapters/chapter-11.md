# 第 11 章：knowledge-sync —— 增量同步与删除同步

> 配套代码：`sync/`
> 可运行 Demo：`demo/Chapter11Demo.java`（第 11 章部分）

---

## 一、这一章要解决的问题

知识不是一次性的。文档会被修改、被删除、被新增。
**如果知识平台不能跟上数据源的变化，它检索到的就是过期的知识。**

最朴素的方案是**全量重建**：每天重跑一遍所有文档。
看一组数字就知道它为什么不可行：

> 假设一个知识库有 10 万篇文档，每天变动 200 篇（0.2%）：
> - **全量重建**：每天 10 万次解析 + 约 100 万次 Embedding 调用 ≈ 数千元/天，
>   而且重建期间服务不可用；
> - **增量同步**：每天 200 次解析 + 约 2000 次 Embedding ≈ 十几元/天。
>
> **两个数量级的成本差距。**

本章要交付的能力：**比对指纹，只处理变化的**。

---

## 二、核心组件一：ContentFingerprint —— 为什么不能用「最后修改时间」

```java
public static ContentFingerprint ofBytes(byte[] content) {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return new ContentFingerprint(toHex(digest.digest(content)));
}
```

### 2.1 mtime 的三宗罪

「用最后修改时间判断有没有变」是最常见的错误选择：

1. **不可靠**：文件复制、解压、跨系统同步都会改变 mtime，内容却一字未改；
2. **精度不够**：某些文件系统的时间戳只到秒级，一秒内的连续修改无法区分；
3. **不可比**：不同系统的时钟不同步，跨平台对不上。

> **内容指纹是对内容本身做哈希，改一个字节就变，没改就绝不会变——
> 这才是「有没有变」这个问题该有的答案。**

用它做增量同步，误判的两个方向都能避免：

- **误判为"没变"（漏检）** → 检索到过期知识；
- **误判为"变了"（误检）** → 白白烧掉 Embedding 的钱。

### 2.2 为什么选 SHA-256 而不是 MD5

MD5 已被攻破（可构造碰撞）。虽然对"判断内容是否变化"这个场景
碰撞概率仍极低，但**用 SHA-256 没有任何额外代价**
（现代 CPU 有硬件加速）。

> **没有理由选择一个已知不安全的算法。**
> 这是安全工程里的基本原则：默认选择当前被认为安全的算法。

### 2.3 一个经典陷阱：显式指定字符集

```java
public static ContentFingerprint ofText(String text) {
    return ofBytes(text.getBytes(StandardCharsets.UTF_8));   // ← 必须显式指定
}
```

用 `String.getBytes()` 而不带字符集，是 Java 里最经典的跨平台陷阱之一——
开发环境（UTF-8）与生产容器（可能是 POSIX/ANSI）算出**不同的指纹**。

表现为：**"本地测得好好的，上了生产每次同步都全量重跑。"**

---

## 三、核心组件二：SyncJob —— 三个动作缺一不可

```java
public enum Action {
    ADD,      // 源端有、本地无
    UPDATE,   // 源端与本地指纹不一致
    DELETE,   // 源端已无、本地仍有   ← 最容易被遗漏，也最危险
    SKIP      // 指纹一致，跳过
}
```

**为什么要把「同步」建模成一个有状态的实体，而不是一个方法调用？**

因为同步是一个**长时运行、可能失败、需要重试、需要审计**的过程。
一个方法调用做不到这些：

- 进程重启了，调用就丢了；
- 失败了，没人知道该从哪重来；
- 想查"过去一周同步了多少次、失败了多少次"，无从查起。

建模成实体后，它就有了自己的生命周期与状态机，
可以被持久化、被查询、被重试——
这正是 **Day12 长时运行 Agent 的思路在知识平台里的复用**。

### 3.1 重试次数必须有限制

```java
public boolean canRetry(int maxAttempts) {
    return status == Status.FAILED && attempt < maxAttempts;
}
```

因为有一类失败是**永久性**的：文件损坏、格式不支持、内容违法合规策略。
对它们重试一万次也只是浪费资源。

> **区分「可重试」与「不可重试」失败，是任务系统的基本功。**

---

## 四、核心组件三：planJobs —— 同步计划算法

```java
public List<SyncJob> planJobs(TenantId tenantId, KnowledgeBaseId kbId,
                              Map<String, SourceItem> currentSource) {
    // ---- 第 1 步：遍历源端 ----
    for (Map.Entry<String, SourceItem> e : currentSource.entrySet()) {
        SyncState known = syncedState.get(stateKey(tenantId, kbId, e.getKey()));
        SyncJob.Action action;
        if (known == null)                                    action = ADD;
        else if (!known.fingerprint().equals(e.getValue().fingerprint())) action = UPDATE;
        else                                                  action = SKIP;
        ...
    }

    // ---- 第 2 步：反查本地，找出源端已删除的 ----
    for (Map.Entry<String, SyncState> e : syncedState.entrySet()) {
        String sourceKey = e.getKey().substring(prefix.length());
        if (!currentSource.containsKey(sourceKey)) {
            jobs.add(SyncJob.of(tenantId, kbId, e.getValue().documentId(),
                    sourceKey, SyncJob.Action.DELETE, e.getValue().fingerprint()));
        }
    }
    return jobs;
}
```

### 4.1 第 2 步是整个增量同步里最容易被漏掉的一步

人的直觉是"同步 = 把新的拿过来"，于是只写了第 1 步。
但同步的完整定义是**"让本地与源端一致"**——
不一致是**双向**的。

少了第 2 步，删掉的文档会永远留在向量库里，
变成检索得到的**「幽灵知识」**：

> 轻则答案过期，重则泄漏已删除的敏感内容。
> 更麻烦的是它**几乎不会被测试发现**——
> 功能测试关注的是"加了能不能搜到"，
> 很少有人测"删了还能不能搜到"。

### 4.2 为什么先产出「计划」再执行

为什么不直接在扫描时执行？因为**「决定做什么」和「做」必须分开**：

- 计划可以先打印出来人工确认（"这次要删 3000 篇，确定吗？"）；
- 计划可以持久化，执行中断后从断点继续；
- 计划可以限流（一次不要提交 10 万个任务）；
- 计划可以审计（"上周三到底删了哪些？"）。

> **把「决策」变成一份可检查的数据，是让自动化系统变得可信的关键。**

### 4.3 提交时 DELETE 必须真的 remove

```java
switch (job.action()) {
    case ADD, UPDATE -> syncedState.put(key, new SyncState(...));
    // DELETE 必须真的把状态删掉，否则下次扫描又会把它判成 ADD，无限循环
    case DELETE -> syncedState.remove(key);
    case SKIP -> { }
}
```

如果 DELETE 只是"标记"而不删除记录，
下次扫描时这条记录又会被判成 ADD，
于是「删除 → 重新入库 → 再判定删除」**无限循环**。

---

## 五、运行 Demo

```bash
java -cp target/classes:<依赖> com.zero.ai.agentstudy.day15knowledgeplatform.demo.Chapter11Demo
```

真实输出（第 11 章部分）：

```
[1] 内容指纹
  文本「差旅报销标准 v1」指纹 f1 = 6e845d1bab2cc2ee…
  同样的文本再算一次      指纹 f2 = 6e845d1bab2cc2ee…  equals(f1) = true
  文本改成「…v2」         指纹 f3 = af1a001ff6e17078…  equals(f1) = false

[2] 首次同步：全部是 ADD
  源端 3 篇，本地 0 篇 → 计划 3 个任务，其中 ADD 3 个
  提交后，本地已同步条目数：3

[3] 第二次同步：只改了 1 篇，删了 1 篇
  生成的同步计划：
    UPDATE   policy/reimburse.md
    DELETE   policy/invoice.md

[4] 提交删除后
  提交后，本地已同步条目数：2（应为 2）
```

注意 `travel.md` 没有出现在计划里——指纹一致，直接跳过。

---

## 六、避坑指南

| 坑 | 后果 | 对策 |
| --- | --- | --- |
| 用 mtime 判断变化 | 误检导致全量重跑，白烧钱 | 用内容指纹 SHA-256 |
| `getBytes()` 不指定字符集 | 跨环境指纹不一致 | 显式 `StandardCharsets.UTF_8` |
| 只实现 ADD/UPDATE，漏掉 DELETE | 幽灵知识：源删了，向量还在 | 反查本地状态表，补 DELETE |
| DELETE 只标记不删除 | 下次判定为 ADD，无限循环 | `syncedState.remove(key)` |
| 边扫描边执行 | 无法人工确认、无法断点续跑、无法审计 | 先产出计划，再执行 |
| 无限重试 | 永久性失败浪费资源 | `canRetry(maxAttempts)` 限制次数 |
| 同步阻塞主流程 | 接口超时、连接耗尽 | 异步化 + 队列 + 状态机（Day12 思路） |

---

## 七、面试题

**Q1：增量同步为什么必须用内容指纹，而不能用最后修改时间？**

> mtime 有三宗罪：
> 1. **不可靠**——文件复制、解压、跨系统同步都会改变 mtime，内容却一字未改；
> 2. **精度不够**——某些文件系统时间戳只到秒级；
> 3. **不可比**——不同系统时钟不同步。
>
> 内容指纹是对内容本身做哈希，**改一个字节就变，没改就绝不会变**，
> 这才是"有没有变"这个问题该有的答案。
>
> 附带一个工程细节：计算文本指纹时必须**显式指定 UTF-8**，
> 否则开发环境和生产容器（可能是 POSIX/ANSI）会算出不同的指纹，
> 表现为"本地正常，上了生产每次都全量重跑"。

**Q2：「删除同步」为什么容易被忽略？后果有多严重？**

> 因为人的直觉是"同步 = 把新的拿过来"，
> 于是在实现时只遍历源端，忘了反查本地。
>
> 后果是**幽灵知识**：源端文档已删除，但它的 Chunk 和向量还在库里，
> 用户依然能通过提问获得那段本应消失的内容。
> 轻则答案过期，重则**泄漏已删除的敏感信息**——这是实打实的合规事故。
>
> 更麻烦的是它**几乎不会被测试发现**，
> 因为功能测试关注的是"加了能不能搜到"，很少有人测"删了还能不能搜到"。
>
> 正确做法：同步计划必须包含**反查本地**这一步，
> 找出"本地有记录但源端已无"的条目，生成 DELETE 任务。

**Q3：为什么要先生成「同步计划」再执行，而不是边扫描边执行？**

> 因为「决定做什么」和「做」是两件不同的事，分开有四个收益：
> 1. **可人工确认**——"这次要删 3000 篇，确定吗？"；
> 2. **可断点续跑**——计划持久化，中断后从断点继续；
> 3. **可限流**——一次不要提交 10 万个任务；
> 4. **可审计**——"上周三到底删了哪些？"。
>
> **把「决策」变成一份可检查的数据，是让自动化系统变得可信的关键。**

---

## 八、本章小结

- **内容指纹（SHA-256）** 是增量同步的前提，mtime 不可靠。
- **三个动作 ADD / UPDATE / DELETE 缺一不可**，DELETE 靠反查本地实现。
- **先出计划再执行**，让同步可确认、可续跑、可限流、可审计。
- **DELETE 必须真的移除状态**，否则会陷入"删除→重加"的无限循环。
- **重试次数必须有限**，永久性失败不该反复重试。

下一章，我们扩展知识的**来源**：让平台能自己去网页上采集。

---

> **第 11 章结束。** 输入「继续」，进入 **第 12 章：knowledge-browser —— 网页采集与连接器生态**。

# 第 6 章：knowledge-embedding —— 向量化与「模型锁版本」铁律

> 配套代码：`embedding/`
> 可运行 Demo：`demo/Chapter06Demo.java`

---

## 一、这一章要解决的问题

到第 5 章为止，我们已经能把一份 PDF 切成一堆 `Chunk`。但 `Chunk` 只是一段字符串——
**字符串之间无法计算相似度**，我们不知道「员工出差怎么报销」和「差旅费用报销标准」是不是一回事。

**Embedding 要做的，就是把「文本」映射到「向量空间中的一个点」，让语义相近的文本在空间里也相近。**
做完这一步，「检索」就从「字符串匹配」变成了「在向量空间里找最近邻」——一个完全不同的范式。

本章要交付的能力：

```
List<Chunk>  ──(EmbeddingService)──▶  List<EmbeddedChunk>  ──(第 7 章)──▶  写入双索引
```

---

## 二、核心抽象一：EmbeddingVector —— 为什么向量不能是裸 `float[]`

### 2.1 代码

```java
public record EmbeddingVector(String model, int dimension, float[] values, boolean normalized) {

    public EmbeddingVector {
        // ... fail-fast 校验
        if (values.length != dimension) {
            throw new KnowledgeException(KnowledgeErrorCode.EMBEDDING_FAILED,
                    "向量维度不一致：声明 " + dimension + "，实际 " + values.length);
        }
        // 防御性拷贝：record 的不可变性不能只靠约定，数组是可变引用
        values = Arrays.copyOf(values, values.length);
    }
}
```

### 2.2 为什么必须携带 `model` 与 `dimension`

这是本章**最重要**的设计。看一个真实场景：

> 团队先用 `text-embedding-3-small`（1536 维）跑了 10 万篇文档。
> 半年后为了提升效果换成 `bge-large`（1024 维），
> 代码一改就上线了，**但没有重建历史向量**。

结果是什么？库里同时存在 1536 维和 1024 维的向量。查询时：

- 用新模型编码查询 → 得到 1024 维向量；
- 与库里那 10 万条 1536 维的旧向量计算余弦相似度 → **维度不匹配**。

如果用的是支持异维度比较的库，它会静默地返回一堆随机分数；
如果会抛异常，那还算走运。最糟的情况是——**新旧向量恰好维度相同但来自不同模型**，
这时候计算完全合法，返回值也"看起来正常"，但**排序结果是纯粹的噪声**。

> **这个现象有个专门的名字：向量漂移（Vector Drift）。**
> 它的可怕之处在于**不报错**。系统照常运行，接口照常返回 200，
> 只是检索质量在不知不觉中崩塌，而且没人知道是从哪天开始的。

有了 `EmbeddingVector`，这个错误在**比较的第一行**就会被拦下：

```java
public void requireComparable(EmbeddingVector other) {
    if (this.dimension != other.dimension) {
        throw new KnowledgeException(KnowledgeErrorCode.EMBEDDING_FAILED,
                "向量维度不一致，无法比较：" + this.dimension + " vs " + other.dimension);
    }
    if (!this.model.equals(other.model)) {
        throw new KnowledgeException(KnowledgeErrorCode.EMBEDDING_FAILED,
                "向量来自不同模型，无法比较：" + this.model + " vs " + other.model
                        + "。更换 Embedding 模型必须全量重建向量");
    }
}
```

**把规则写进类型，而不是写进文档。** 文档会被忘记，类型不会。

### 2.3 为什么必须防御性拷贝

`record` 的字段是不可变的，但 `float[]` 是**引用**。
`record` 只保证"这个引用不会指向别的数组"，不保证"数组内容不被修改"。

如果不拷贝，调用方拿到 `values()` 后修改第 0 个元素，
库里那条向量就悄悄变了——而它是不可变的值对象，本不该发生这种事。
**不可变性要么做彻底，要么别声称自己不可变。**

### 2.4 为什么默认 L2 归一化

```java
public static float[] l2Normalize(float[] v) {
    double sum = 0.0;
    for (float x : v) { sum += (double) x * x; }
    if (sum <= 0.0) {
        return new float[v.length];   // 零向量：返回零向量，而不是除零得到 NaN
    }
    // ...
}
```

**归一化后，余弦相似度退化为点积。** 这意味着检索时每对比较可以省掉
一次开方和一次除法。在百万级向量的暴力扫描里，这是肉眼可见的性能差异。

**零向量的处理值得单独说**：直接返回零向量，而不是让 `v/0` 产生 `NaN`。
`NaN` 一旦进入向量库，任何与它的相似度都是 `NaN`，
而 `NaN` 在排序比较中行为诡异（`Double.compare` 认为 `NaN` 最大），
会让排序彻底错乱，且极难排查。

---

## 三、核心抽象二：EmbeddingModel 端口

```java
public interface EmbeddingModel {
    String modelId();
    int dimension();
    EmbeddingVector embed(String text);
    List<EmbeddingVector> embedAll(List<String> texts);
}
```

### 3.1 为什么批量方法是一等公民

**Embedding 调用的瓶颈从来不是算力，而是网络往返。**

一次请求 100 条 vs 100 次请求各 1 条：服务端算力几乎一样，
但后者要多付 99 次 RTT。跨公网常常是 20~80ms/次，
**整体耗时能差一个数量级**。

把 `embedAll` 放进接口而不是让调用方循环调 `embed`，
是为了让**批处理成为默认路径**——
只有接口里有它，实现者才会去优化它。

### 3.2 两个适配器

| 适配器 | 用途 | 特点 |
| --- | --- | --- |
| `HashingEmbeddingModel` | 零依赖可离线运行 | 见下方 3.3，字面相似 |
| `SpringAiEmbeddingModel` | 接真实模型 | 适配 Spring AI，自动归一化 + 批量调用 |

`SpringAiEmbeddingModel` 是六边形架构里最典型的**适配器**——
它唯一的工作就是把 Spring AI 的类型翻译成我们领域内部的类型。
**除了这一个类，整个 Day15 没有任何一行代码依赖 Spring AI。**

注意 `modelId` 必须由调用方**显式传入**，而不是从 Spring AI 的 bean 反射读取：

```java
public SpringAiEmbeddingModel(org.springframework.ai.embedding.EmbeddingModel delegate, String modelId) {
    if (modelId == null || modelId.isBlank()) {
        throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT,
                "modelId 不能为空：更换 Embedding 模型必须全量重建向量，故必须显式声明模型标识");
    }
}
```

因为 `modelId` 要写进每条向量用于「锁版本」校验，
它必须是**稳定、可读、可比对**的，不能依赖框架内部实现细节。

### 3.3 关于 HashingEmbeddingModel 的诚实说明

它实现的是 **the hashing trick（特征哈希）**——一种真实的、无需训练的向量化方法：
把每个 token 用两个独立哈希函数映射到 `[0, dimension)` 的下标与一个正负号，
按子线性词频加权累加，最后 L2 归一化。
scikit-learn 的 `HashingVectorizer`、Vowpal Wabbit 都用它。

**它能做什么**：刻画**字面相似**。
**它不能做什么**：刻画**语义相似**。

「支持远程办公吗」与「弹性工作制」在字面上毫无重叠，它算出的相似度接近 0；
而真正的稠密模型能把它们映射到相近的向量。

> **这不是缺陷，而是本章刻意保留的教学设计。**
> 它用一个可运行的事实告诉你两件事：
> 1. 生产环境必须用**真实的**稠密 Embedding 模型；
> 2. 只靠向量**同样不够**——这正是第 7 章 Hybrid Search 存在的理由。

---

## 四、核心抽象三：EmbeddingService —— 省钱的两个抓手

### 4.1 抓手一：批处理

把 N 次网络往返压成 `ceil(N / batchSize)` 次。

### 4.2 抓手二：去重缓存（附一个真实踩过的坑）

```java
private String cacheKey(String text) {
    return model.modelId() + "|" + text;
}
```

**为什么缓存键必须把 `modelId` 拼进去？**

因为同一段文本在不同模型下的向量完全不同。如果缓存键只有文本，
一旦换了模型，新请求会命中旧模型的向量——
于是库里混进一批「看起来正常、实则来自另一个向量空间」的脏数据，
检索质量缓慢劣化且无人察觉。**把 `modelId` 拼进 key，换模型即等价于自动换缓存。**

### 4.3 一个真实的缺陷：缓存在批处理中被架空

初版实现是这样的：

```java
// 1) 一次性查缓存
for (int i = 0; i < n; i++) {
    EmbeddingVector cached = cache.get(cacheKey(chunks.get(i).text()));
    if (cached != null) { results[i] = cached; cacheHits++; }
    else { missIndexes.add(i); }
}
// 2) 对 missIndexes 分批调用模型
for (int from = 0; from < missIndexes.size(); from += batchSize) { ... }
```

**问题**：第 1 步在任何模型调用之前一次性完成，
所以同一批内、甚至相邻批之间的重复文本**都查不到缓存**——
缓存命中率长期为 0。

而现实中重复内容（页眉、页脚、免责声明、模板段落）
**几乎总是集中出现在同一次调用里**。只做第 1 步的话，
看起来"功能已实现"，实际上一分钱也没省下。

Demo 实测，修复前后差距巨大：

```
修复前：批量调用 4 次，缓存命中率 0.0%
修复后：实际调用模型 1 次，缓存命中率 73.3%
```

**修复方案**：在分批循环内部**二次查缓存**，并**按文本去重**后再提交：

```java
for (int from = 0; from < missIndexes.size(); from += batchSize) {
    Map<String, List<Integer>> pending = new LinkedHashMap<>();
    for (int idx : batchIndexes) {
        String text = chunks.get(idx).text();
        EmbeddingVector cached = cache.get(cacheKey(text));
        if (cached != null) { results[idx] = cached; cacheHits++; }
        else { pending.computeIfAbsent(text, k -> new ArrayList<>()).add(idx); }
    }
    if (pending.isEmpty()) continue;   // 全部命中缓存，无需调用模型

    List<String> distinctTexts = new ArrayList<>(pending.keySet());
    List<EmbeddingVector> vectors = model.embedAll(distinctTexts);
    batchInvocations++;
    for (int k = 0; k < distinctTexts.size(); k++) {
        cache.put(cacheKey(distinctTexts.get(k)), vectors.get(k));
        for (int originIdx : pending.get(distinctTexts.get(k))) {
            results[originIdx] = vectors.get(k);   // 一处计算，多处复用
        }
    }
}
```

### 4.4 顺序还原：一个极易写错的地方

缓存命中是乱序的、批量调用是分块的。
如果直接把结果按处理顺序 `append`，**切片和向量就会错位**——
表现为「检索到的片段和它标注的章节路径对不上」。

这种 bug 不会抛异常，只会让**溯源功能静默失真**，
而溯源恰恰是企业知识平台的生死线。所以实现上用 `EmbeddingVector[] results`
按原始下标回填，最后统一装配。

### 4.5 为什么这里没有重试逻辑

**重试属于编排层**（第 11 章的 SyncJob、第 12 章的任务队列）的职责，
不是领域服务的职责。

把重试塞进领域服务，会导致「一次上层重试 × 内部三次重试」的指数放大，
这是分布式系统里经典的**雪崩制造机**。领域服务失败就明确失败，
由编排层决定是重试、还是进死信队列。

---

## 五、运行 Demo

```bash
mvn -o compile
java -cp target/classes:<依赖> com.zero.ai.agentstudy.day15knowledgeplatform.demo.Chapter06Demo
```

真实输出（节选）：

```
[1] 文本向量化：相似文本的向量确实更接近
  sim(A, B) = 0.8145   ← 近似同义句，应当较高
  sim(A, C) = -0.1287  ← 主题无关，应当较低

[2] 归一化：余弦相似度退化为点积
  向量模长 ||v|| = 1.000000（应为 1.0）

[3] 批处理与去重缓存
  实际调用模型次数：1 次
    → 30 条文本里只有 4 条是不同内容，所以真正付费的只有这 4 条
  缓存命中率：73.3%

[4] 模型锁版本：不同模型的向量禁止比较（fail-fast）
  ✓ 已拦截跨模型比较：向量来自不同模型，无法比较：
    hashing-trick-v1 vs another-model-v2。更换 Embedding 模型必须全量重建向量
  ✓ 已拦截跨维度比较：向量维度不一致，无法比较：256 vs 128
```

---

## 六、避坑指南

| 坑 | 后果 | 本章的对策 |
| --- | --- | --- |
| 混用不同模型的向量 | 检索质量静默崩塌（向量漂移） | `EmbeddingVector.requireComparable` fail-fast |
| 向量未归一化 | 相似度不可比；长文本系统性占优 | 适配器层统一 L2 归一化 |
| 零向量除零 | 产生 `NaN`，排序彻底错乱 | `l2Normalize` 显式返回零向量 |
| 逐条调用模型 | 网络往返 × N，慢一个数量级 | `embedAll` 批量 + 批内去重 |
| 缓存键不含 modelId | 换模型后命中旧向量 | `modelId + "|" + text` |
| 缓存查在批处理之前 | 命中率恒为 0，形同虚设 | 批内二次查缓存（见 4.3） |
| 结果按处理顺序装配 | 切片与向量错位，溯源失真 | 按原始下标回填 |
| 领域服务内置重试 | 重试放大，制造雪崩 | 重试交给编排层 |

---

## 七、面试题

**Q1：为什么更换 Embedding 模型必须全量重建向量？**

> 不同模型把文本映射到**完全不同的向量空间**。
> 即使维度相同，同一个词在 A 模型和 B 模型下的向量方向也没有任何对应关系。
> 在新空间里计算「查询向量与旧向量的距离」是没有数学意义的，
> 排序结果等同于随机。
>
> 更危险的是它**不报错**——系统照常返回 200，
> 只是检索质量在不知不觉中崩塌，且无从追溯是从哪天开始的。
>
> 安全姿势：**新建库 → 双写 → 对比验证 → 切换 → 下线旧库**。
> 这样切换是瞬时的、可回滚的，且验证期间新旧两套可以对比。

**Q2：Embedding 阶段如何降低成本？**

> 三个抓手：
> 1. **批处理**：把 N 次 RTT 压成 N/batch 次；
> 2. **去重缓存**：页眉页脚、免责声明、模板段落大量重复，
>    实测命中率可达 70%+，等于直接省掉七成的调用费；
>    注意缓存键要含 modelId，且要在批处理**内部**二次查缓存；
> 3. **增量同步**（第 11 章）：只处理变化的文档。
>
> 补充一条：**先做去重再入库**。如果缓存命中率长期高于 60%，
> 说明文档本身高度重复，应该在入库前就把重复内容合并掉。

**Q3：向量为什么要归一化？**

> 归一化后余弦相似度退化为点积，检索时可省掉大量浮点运算。
> 更重要的是它消除了**文本长度对向量模长的影响**——
> 未归一化时，长文本的向量模长天然更大，
> 会在相似度比较中获得系统性优势，而这并不是我们想要的"相关性"。

**Q4：`float[]` 放在 `record` 里，真的不可变吗？**

> 不。`record` 保证的是"字段引用不变"，而数组是**引用类型**，
> 数组内容仍可被外部修改。必须在构造器里做防御性拷贝，
> 并在 `values()` 访问器里再次拷贝返回。
> **不可变性要么做彻底，要么别声称自己不可变。**

---

## 八、本章小结

- **向量必须携带 model + dimension + normalized**，这是「模型锁版本」的落地方式，也是向量漂移的第一道防线。
- **归一化让余弦退化为点积**，同时消除了文本长度带来的偏差。
- **EmbeddingModel 是端口**，换供应商只需换适配器，业务代码一行不改。
- **省钱的两个抓手是批处理与去重缓存**，而缓存必须放在批处理**内部**才能真正生效。
- **顺序还原**必须按原始下标回填，否则溯源会静默失真。
- **重试不在领域服务里做**。

下一章，我们把 `EmbeddedChunk` 写进双索引，并直面那个核心问题：
**为什么只有向量是不够的。**

---

> **第 6 章结束。** 输入「继续」，进入 **第 7 章：knowledge-retriever —— Hybrid Search 与 RRF 融合**。

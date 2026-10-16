# Day15：Enterprise AI Knowledge Platform（企业级 AI 知识平台）

> 《Java 程序员转 AI Agent 架构师（120 天）》训练营 · Day15 学习资料
>
> 本文档为「自包含学习资料」：即使你以后完全不看聊天记录，只打开这份 README，也能独立、完整地重新学习 Day15 的全部内容。
>
> 阅读对象：拥有多年 Java / Spring Boot 经验、已完成 Day1~Day14、希望成为企业级 AI Agent 架构师的工程师。
>
> 技术栈：Java 21（LTS）· Spring Boot 4.1.0 · Spring AI 2.0.0 · Spring Modulith · PostgreSQL 17 + pgvector · Redis 8 · DDD · Hexagonal Architecture · Virtual Threads · Micrometer · OpenTelemetry · Testcontainers。

---

## 〇、写在最前面：Day15 在整个训练营中的位置

在过去的 14 天里，我们一步步把一个「只会聊天的大模型」，改造成了「会说话、会用工具、有记忆、能规划、能自动化办公、能对接企业微信」的 AI Agent：

- **Day1 LLM**：搞清楚大模型是什么，Token、上下文窗口、生成原理。
- **Day2 API**：用 Java 直接调用 Chat Completions，理解 message 数组、system prompt、流式、结构化输出。
- **Day3 Tool / Function Calling**：让模型「会用工具」，把外部能力接进来。
- **Day4 Memory**：让 Agent「有记忆」，能记住对话历史与用户画像。
- **Day5 RAG**：让 Agent 能读懂「企业私有知识」，用检索增强生成回答问题。
- **Day6 Workflow**：让 Agent 能按流程编排多个步骤。
- **Day7 MCP**：让 Agent 通过标准协议连接外部数据源与工具。
- **Day8 Multi-Agent**：多个 Agent 协作分工。
- **Day9 Browser Agent**：让 Agent 会操作浏览器。
- **Day10 Planning Agent**：让 Agent 会拆解任务、制定计划。
- **Day11 Human-in-the-loop**：让人类可以介入 Agent 的关键决策。
- **Day12 Long Running Agent**：让 Agent 能跑长任务、断点续跑。
- **Day13 AI Office Agent**：让 Agent 会写 Word、Excel、PPT、发邮件。
- **Day14 Enterprise AI WeChat Agent**：让 Agent 接入企业微信，成为企业内的智能助手。

到 Day14 为止，我们的 Agent 已经「能力齐备」。但它有一个**根本性的、决定商业成败的短板**：

> **它没有一个企业级的「知识中台」。**

Day5 我们确实做过 RAG，但那是一个**教学级的 RAG Demo**：内存向量库、单一知识库、没有租户、没有权限、没有版本、没有增量同步、不支持 PDF/Word/PPT、没有 Hybrid Search、没有 Reranker。它能证明「RAG 原理可行」，但**它无法投入企业生产**。

Day15 的目标，就是把 Day5 的「玩具 RAG」升级为一个**真正能投入企业生产、支持多租户、可商业化 SaaS 的 Enterprise AI Knowledge Platform（企业级 AI 知识平台）**。

这是一个分水岭：

> **在此之前，我们做的是「AI Agent」。从今天开始，我们做的是「AI 产品」。**

一个 AI Agent 是一个功能；一个 Knowledge Platform 是一门生意。这就是 Day15 的分量。

---

## 一、今天的学习目标

学完今天，你必须能够真正回答 / 做到以下几件事：

1. **说清楚为什么企业需要 AI Knowledge Platform，而不仅仅是 RAG。** 能从商业模式、ROI、SaaS 化价值三个角度论证。
2. **画出并讲解一个支持百万文档、多租户的企业级知识平台完整架构。** 包括 Document Pipeline、Parser、Chunk、Embedding、Metadata、Hybrid Search、Reranker、ACL、Workspace、Sync 全链路。
3. **说清楚 Google Vertex AI Search、Azure AI Search、OpenAI File Search、Notion AI、Confluence AI、Guru、Glean、LlamaIndex、Haystack 各自的定位与差异**，并能对比它们的架构取舍。
4. **理解知识平台的底层概念**：Knowledge Base、Document、Chunk、Embedding、Metadata、Workspace、Tenant、ACL、Hybrid Search、BM25、Vector Search、Reranker、Knowledge Memory、Index、Incremental Sync、Delete Sync。
5. **用 Java 21 + Spring Boot 4.1 + Spring AI 2.0 实现一个生产级的知识平台**，包含 14 个模块，遵循 DDD + Hexagonal + Spring Modulith。
6. **完成一条完整的文档处理流水线**：上传 → 解析 → OCR → Chunk → Metadata → Embedding → 存 pgvector → Hybrid Search → Reranker → ACL 过滤 → 返回。
7. **掌握至少 10 个企业知识平台的经典坑**及其优化手段。
8. **让整个平台能够无缝集成到 ZeroHub AI Agent Platform**，供 Office Agent、WeChat Agent、Customer Service、Search Agent 统一调用。

---

## 二、企业知识平台的发展历史

要理解「为什么今天要做知识平台」，必须先理解「企业管理知识」这件事的演进史。企业对知识的管理，经历了五个清晰的时代。

### 2.1 第一代：文件与共享盘时代（1990s ~ 2005）

最早，企业知识就是一堆 Word、Excel、PPT，散落在个人电脑和部门共享盘（File Server / Samba / FTP）里。

- **形态**：文件夹 + 命名规范（`产品文档_v3_final_最终版_真的最终.docx`）。
- **检索**：靠文件名和记忆。找一份文档，得问「这个东西当年是谁写的」。
- **痛点**：知识散落、版本混乱、无法检索、人走知识就走。

### 2.2 第二代：企业门户与 CMS 时代（2005 ~ 2012）

以 SharePoint、Confluence、各类 Wiki、CMS 为代表，企业开始有了「集中式的知识库」。

- **形态**：结构化的页面、空间（Space）、树形目录、权限。
- **检索**：基于关键词的**全文检索（Full-Text Search）**，底层往往是 Lucene / Solr / Elasticsearch。
- **进步**：知识集中、有权限、有版本、能搜索。
- **痛点**：**只能「搜到文档」，不能「回答问题」。** 你搜「报销流程」，它给你 20 篇包含「报销」二字的文档，具体流程还得自己一篇篇读。检索的是「关键词匹配」，不是「语义理解」。

### 2.3 第三代：企业搜索时代（2012 ~ 2020）

以 Elasticsearch 生态、Google Search Appliance（后来的 Cloud Search）、以及各类企业搜索引擎为代表。

- **形态**：跨系统的统一搜索，能索引邮件、文档、工单、代码、聊天记录。
- **检索**：倒排索引 + BM25 排序 + 一些机器学习排序（Learning to Rank）。
- **进步**：跨源、快、可扩展。
- **痛点**：**本质仍是「关键词匹配」。** 用户问「我们公司支持远程办公吗」，如果文档里写的是「弹性工作制」，关键词对不上，就搜不到。语义鸿沟（Semantic Gap）没有被跨越。

### 2.4 第四代：向量检索与 RAG 时代（2020 ~ 2023）

Transformer 带来的 Embedding 技术，让「语义检索」成为可能。2022 年 ChatGPT 引爆后，RAG（Retrieval-Augmented Generation）成为标准范式。

- **形态**：文档切成 Chunk → Embedding 成向量 → 存向量库 → 用户提问也 Embedding → 向量相似度召回 → 拼进 Prompt → LLM 生成答案。
- **进步**：**第一次做到了「语义理解」+「自然语言回答」。** 问「支持远程办公吗」，即使文档写「弹性工作制」，向量也能匹配上，还能直接给你一句人话答案。
- **痛点（这是 Day15 要解决的核心）**：
  - 只有**向量检索**，丢失了关键词精确匹配能力（搜产品型号 `X-2000` 这类精确 token，向量反而不如 BM25）。
  - 召回质量不稳定，缺少 **Reranker**。
  - 大多是**单知识库、单租户**的 Demo，无法支撑 SaaS。
  - 没有 **ACL 权限**，A 部门的机密文档可能被 B 部门检索到——这是**致命的合规事故**。
  - 没有**增量同步、删除同步、版本管理**，文档更新了向量库还是旧的。
  - 只支持 TXT/MD，不支持真实企业里占 90% 的 **PDF / Word / Excel / PPT / 扫描件**。

### 2.5 第五代：AI Knowledge Platform 时代（2023 ~ 至今）

这是我们**今天所处的时代**，也是 Day15 的主题。业界（Google、Microsoft、OpenAI、Glean、Notion）在 2023 年之后，不约而同地把「RAG」升级为「AI Knowledge Platform」。

一个 AI Knowledge Platform = RAG 内核 + 企业级工程能力：

```
AI Knowledge Platform
= 语义检索(Vector)  + 关键词检索(BM25) + Reranker      ← 检索质量
+ 多格式解析(PDF/Office/OCR)                            ← 数据接入
+ 多租户(Tenant) + 工作空间(Workspace) + 权限(ACL)      ← SaaS 与合规
+ 版本 + 增量同步 + 删除同步                            ← 数据一致性
+ 元数据(Metadata) + 标签 + 分类                        ← 治理
+ 可观测性(Metrics/Trace) + 高可用                      ← 生产运维
+ 统一 API + 供多个 Agent 调用                          ← 知识中台
```

**一句话总结这段历史**：企业管理知识的演进，是一条「从『存文件』到『搜关键词』再到『懂语义』最终到『成为可被 AI 消费的知识中台』」的路。今天我们站在第五代的起点上，亲手把它建出来。

---

## 三、为什么企业需要 AI Knowledge Platform（而不仅仅是 RAG）

这是本章最重要的一节。请务必想清楚：**RAG 是一种技术，Knowledge Platform 是一种产品/资产。** 企业买单的从来不是技术，而是资产和结果。

### 3.1 从「Prompt 塞知识」到「平台管理知识」

新手常犯的错误：把企业知识直接写进 System Prompt。

为什么企业**绝对不会**这么做？

1. **上下文窗口有限**：企业知识动辄几十万篇文档，几百 GB，物理上塞不进任何模型的上下文。
2. **成本爆炸**：每次请求都携带海量知识，Token 成本无法承受。
3. **无法更新**：知识改了，得改 Prompt、重新发版，无法实时。
4. **无权限**：Prompt 里的知识对所有人可见，无法做「A 能看、B 不能看」。
5. **无审计**：无法追溯「这条回答是基于哪份文档的哪一段」。

所以企业需要一个**平台**，把知识「治理」起来：存储、切分、索引、鉴权、版本、检索、审计。

### 3.2 为什么 RAG 只是知识平台的一部分

RAG 解决的是「**检索 + 生成**」这一个环节。但一个能投产的知识平台，检索只占大约 20% 的工程量，剩下 80% 是：

| 维度 | 纯 RAG Demo | Enterprise Knowledge Platform |
| --- | --- | --- |
| 数据接入 | 手动喂 TXT | 上传 PDF/Word/Excel/PPT/图片 + 浏览器采集 + MCP 数据源 |
| 检索方式 | 只有向量 | Vector + BM25 全文 + Reranker（Hybrid Search） |
| 多租户 | 无 | Tenant 隔离，一套系统服务成千上万家企业 |
| 权限 | 无 | ACL，行级/文档级权限，防止知识泄漏 |
| 数据一致性 | 无 | 版本、增量同步、删除同步 |
| 治理 | 无 | Metadata、标签、分类、Workspace |
| 可观测 | 无 | Metrics、Trace、召回率监控 |
| 被消费 | 单点问答 | 统一 API，供 Office/WeChat/客服/搜索 Agent 调用 |

**结论**：RAG 是引擎，Knowledge Platform 是整辆车。你不能把一台发动机卖给客户当汽车用。

### 3.3 商业模式、ROI 与 SaaS 化价值

从架构师的视角，你必须能向老板/客户讲清楚「为什么值得投资」：

- **商业模式**：知识平台是天然的 SaaS。多租户架构下，一套系统服务 N 个客户，边际成本极低，可按「席位 / 文档量 / 检索次数」计费。Glean、Notion AI、Guru 都是数十亿美金估值的 SaaS 公司。
- **ROI（投资回报）**：
  - **降本**：客服、IT、HR 的重复性问答，70% 可被知识平台自动回答，直接减少人力。
  - **提效**：研发找 API 文档、法务找合同条款、销售找产品资料，从「几十分钟翻文档」变成「几秒钟得答案」。
  - **留存知识**：员工离职，知识沉淀在平台，不随人走。
- **SaaS 化价值**：一旦做成多租户 + ACL + Workspace，就从「给一家公司做的内部工具」升级为「可卖给一万家公司的产品」，估值逻辑完全不同。

这就是为什么 Google、Microsoft、OpenAI 都在**抢着建 Knowledge Platform**——它是 AI 时代企业软件的**入口和地基**。

---

## 四、业界主流 AI Knowledge Platform 全景（竞品分析）

作为架构师，你必须知道「巨头们是怎么做的」，才能设计出对标生产标准的系统。下面逐一分析。

### 4.1 Google Vertex AI Search（原 Enterprise Search / Gen App Builder）

- **定位**：Google Cloud 上的企业级搜索 + RAG 一站式服务。
- **特点**：开箱即用的多格式解析、内建 Hybrid Search（Google 强项就是搜索）、内建 Reranker、支持结构化 + 非结构化数据、深度集成 Gemini。
- **架构启示**：Google 把「搜索质量」做到极致，Hybrid + Rerank 是标配。这印证了 Day15 必须做 Hybrid Search + Reranker。

### 4.2 Azure AI Search（原 Azure Cognitive Search）

- **定位**：微软云上的检索服务，深度集成 Azure OpenAI。
- **特点**：强大的 **Skillset（技能管线）**，把 OCR、实体识别、Chunk、Embedding 编排成一条 Indexer 流水线；原生支持向量 + 全文 + 语义排序的混合检索。
- **架构启示**：它的「Indexer + Skillset 管线」思想，正是我们 Document Pipeline 的设计原型——**把处理过程做成可编排的管线**。

### 4.3 OpenAI File Search（Assistants API / Responses API）

- **定位**：OpenAI 托管的最简 RAG，上传文件即可检索。
- **特点**：极致简单，自动 Chunk、自动 Embedding、自动检索。但**几乎没有企业治理能力**（弱权限、弱租户、黑盒）。
- **架构启示**：它代表「易用性的天花板」，但也说明**易用 ≠ 企业级**。企业要的可控、可审计、可私有化，它给不了——这正是自建平台的价值。

### 4.4 Notion AI

- **定位**：文档协作工具内建的 AI 问答。
- **特点**：知识天然结构化（Notion 本身就是文档库），AI 直接在自家数据上做 RAG，体验丝滑。
- **架构启示**：**知识平台最好离数据源近**。Notion 的优势在于「数据即知识库」，我们的 Workspace 设计也借鉴了这种「知识天然归属于空间」的模型。

### 4.5 Confluence AI（Atlassian Intelligence）

- **定位**：Confluence Wiki 之上的 AI 问答与摘要。
- **特点**：继承 Confluence 成熟的 **Space（空间）+ 权限体系**，AI 严格遵守原有权限——你搜不到你本来就没权限看的页面。
- **架构启示**：**权限必须前置到检索层**。这直接决定了我们的 ACL 设计：权限过滤必须发生在召回阶段，而不是展示阶段。

### 4.6 Guru

- **定位**：以「知识卡片（Card）」为核心的企业知识管理 SaaS，主打客服、销售场景。
- **特点**：知识以小颗粒「卡片」存在，带**验证机制（Verification）**——每张卡片有 owner 定期确认「知识是否还有效」。
- **架构启示**：知识会**过期**。这启发我们在 Metadata 里加入「有效期 / 最后验证时间」，避免「向量漂移」和「过期知识」。

### 4.7 Glean

- **定位**：企业级「工作助手」，连接公司所有 SaaS（Slack/GDrive/Jira/Confluence...），做统一语义搜索 + 助手。
- **特点**：极强的**连接器（Connector）生态** + **权限感知搜索**（Permission-aware Search）+ 企业知识图谱。是当前企业知识平台的**标杆**。
- **架构启示**：Glean 的核心竞争力是「连接一切数据源」+「严格权限」。我们的 knowledge-sync（增量同步）、knowledge-browser（采集）、MCP 数据源，就是在建自己的「连接器生态」雏形。

### 4.8 LlamaIndex

- **定位**：开源的「数据框架」，专注把数据接入 LLM。
- **特点**：丰富的 Data Connector、Node（≈Chunk）抽象、多种索引结构、Query Engine。是**RAG 工程化的开源标准**。
- **架构启示**：它的 `Node`、`NodeParser`、`Retriever`、`Postprocessor（Rerank）` 抽象，与我们的 Chunk、Parser、Retriever、Reranker 模块一一对应。我们用 Java 重新实现这套抽象。

### 4.9 Haystack

- **定位**：deepset 开源的、以 **Pipeline** 为核心的 LLM/检索框架。
- **特点**：一切皆 Pipeline，Component（组件）用有向图连接，天然支持 Hybrid + Rerank。
- **架构启示**：Haystack 的「Pipeline + Component」思想，与我们的 Document Pipeline、Retrieval Pipeline 高度一致。**管线化 = 可编排 + 可测试 + 可替换**。

### 4.10 一张表看清定位

| 产品 | 类型 | 核心优势 | 我们借鉴什么 |
| --- | --- | --- | --- |
| Vertex AI Search | 云服务 | 搜索质量、Hybrid+Rerank | Hybrid + Rerank 是标配 |
| Azure AI Search | 云服务 | Skillset 管线 | Document Pipeline 管线化 |
| OpenAI File Search | 云服务 | 极简易用 | 易用性目标（但要可控） |
| Notion AI | 应用内建 | 数据即知识库 | Workspace 归属模型 |
| Confluence AI | 应用内建 | 权限继承 | ACL 前置到检索 |
| Guru | SaaS | 知识验证机制 | Metadata 有效期 |
| Glean | SaaS 标杆 | 连接器 + 权限感知 | Sync/Browser/MCP 连接器 |
| LlamaIndex | 开源框架 | 数据接入抽象 | Parser/Chunk/Retriever 抽象 |
| Haystack | 开源框架 | Pipeline 编排 | Pipeline + Component |

**结论**：所有巨头的共识是——**Hybrid 检索 + Reranker + 权限感知 + 管线化 + 多数据源**。这五点，就是 Day15 平台的设计主线。

---

## 五、完整知识体系（核心概念地图）

在动手写代码前，先把 Day15 会反复出现的核心概念一次性讲清楚。建议把这一节当成「术语词典」，后续每一章都会用到。

### 5.1 领域概念（DDD 视角）

- **Tenant（租户）**：一家使用平台的企业/组织。SaaS 的顶层隔离单位。所有数据都必须带 `tenant_id`，租户之间物理/逻辑隔离，绝不串数据。
- **Workspace（工作空间）**：租户内部的知识分区，如「研发空间」「法务空间」「客服空间」。类似 Confluence 的 Space、Notion 的 Teamspace。
- **KnowledgeBase（知识库）**：Workspace 下的一组相关文档集合，是检索的基本作用域。一次检索通常指定「在哪个/哪些知识库里搜」。
- **Document（文档）**：一份上传的原始文件（PDF/Word/…）及其元数据、版本、状态。
- **Chunk（切片）**：文档被切分后的最小检索单元。每个 Chunk 携带自己的文本、元数据、以及所属文档/知识库的引用。
- **Embedding（向量）**：Chunk 文本经 Embedding 模型转成的高维向量，用于语义相似度检索。
- **Metadata（元数据）**：附着在文档/Chunk 上的结构化信息（标题、作者、部门、标签、分类、创建时间、有效期、来源 URL 等），用于过滤与治理。
- **ACL（Access Control List）**：访问控制列表，定义「谁能看哪个知识库/文档」。是防止知识泄漏的核心。

### 5.2 检索概念

- **Vector Search（向量检索）**：把 query 也 Embedding，在向量空间用余弦相似度 / 内积找最近邻。擅长「语义匹配」，弱于「精确 token 匹配」。
- **BM25 / Full-Text Search（全文检索）**：基于词频-逆文档频率的经典排序算法。擅长「精确关键词、专有名词、型号、代码」，弱于「同义改写」。
- **Hybrid Search（混合检索）**：同时跑 Vector + BM25，再用融合算法（如 RRF，Reciprocal Rank Fusion）合并结果。**兼得语义与精确**，是生产标配。
- **Reranker（重排序器）**：对 Hybrid 召回的候选（如 top 50），用更强的 Cross-Encoder 模型逐条精算「query 与 chunk 的相关性」，重新排序取 top 5。**大幅提升最终答案质量**，代价是算力/延迟。
- **Recall（召回率）**：正确答案是否出现在候选集里。检索质量的核心指标。
- **Knowledge Memory（知识记忆）**：把检索到的知识与对话记忆结合，让 Agent 在多轮对话中持续利用知识。

### 5.3 数据一致性概念

- **Version（版本）**：文档更新后保留历史版本，可回溯、可回滚。
- **Incremental Sync（增量同步）**：外部数据源（网页、Confluence…）变化时，只处理变化的部分，而非全量重建。
- **Delete Sync（删除同步）**：源文档删除后，其所有 Chunk 与向量必须**同步删除**，否则会检索到「幽灵知识」——这是极易被忽视、又极其危险的坑。

### 5.4 一图串起所有概念

```
Tenant（企业）
  └── Workspace（研发空间 / 法务空间 …）
        └── KnowledgeBase（API 文档库 / 合同库 …）  ← 检索作用域，受 ACL 保护
              └── Document（一份 PDF/Word …，有 Version）
                    └── Chunk（切片，带 Metadata）
                          └── Embedding（向量，存 pgvector）

检索时： Query → [Vector Search + BM25] → RRF 融合 → Reranker → ACL 过滤 → Top-K 结果
```

记住这张图，Day15 的一切代码都围绕它展开。

---

## 六、Day15 的 14 个模块总览

我们把整个平台按 DDD + Spring Modulith 拆成 14 个模块。每个模块职责单一、边界清晰、可独立测试。后续章节将逐个实现。

| 模块 | 中文 | 职责 | 状态 |
| --- | --- | --- | --- |
| `knowledge-core` | 核心域 | 通用领域模型、值对象、统一异常、统一结果、事件基类 | ✅ 已实现（第 1 章） |
| `knowledge-document` | 文档域 | 文档聚合根、上传、版本、状态机、文档仓储 | ✅ 已实现（第 3 章） |
| `knowledge-parser` | 解析 | PDF/Word/Excel/PPT/MD/HTML/TXT 解析 + 图片 OCR，策略模式 | ✅ 已实现（第 4 章） |
| `knowledge-chunk` | 切分 | Chunk 管线，多种切分策略（定长/语义/结构化） | ✅ 已实现（第 5 章） |
| `knowledge-embedding` | 向量化 | Embedding 管线，批处理 + 去重缓存 + 模型锁版本 | ✅ 已实现（第 6 章） |
| `knowledge-retriever` | 检索 | Vector + BM25 混合检索，RRF 融合，双索引一致性 | ✅ 已实现（第 7 章） |
| `knowledge-reranker` | 重排 | 精排（直通/启发式/CrossEncoder）+ MMR 多样性去重 | ✅ 已实现（第 8 章） |
| `knowledge-workspace` | 工作空间 | Tenant / Workspace / KnowledgeBase 管理 | ✅ 已实现（第 9 章） |
| `knowledge-acl` | 权限 | 访问控制、权限感知检索过滤 | ✅ 已实现（第 10 章） |
| `knowledge-sync` | 同步 | 内容指纹增量同步、删除同步、事件驱动 | ✅ 已实现（第 11 章） |
| `knowledge-browser` | 采集 | 网页抓取端口 + JDK 实现（Playwright 为可选适配器） | ✅ 已实现（第 12 章） |
| `knowledge-api` | 统一 API | 对外 REST API，供各 Agent 调用 | ✅ 已实现（第 13 章） |
| `knowledge-admin` | 管理端 | 授权审计、平台统计、索引一致性检查 | ✅ 已实现（第 14 章） |
| `docs` | 文档 | README / ARCHITECTURE / chapters / API / DATABASE / DEPLOY / ADR / ROADMAP | 🟡 部分（见下） |

模块依赖方向（严格单向，杜绝循环依赖）：

```
api / admin  →  workspace / acl / sync / browser  →  retriever / reranker  →  embedding  →  chunk  →  parser  →  document  →  core
```

`core` 不依赖任何人，被所有人依赖；越靠右越底层、越稳定。这就是 Clean Architecture 的「依赖只能指向更稳定的方向」。

---

## 七、如何独立运行本项目

本项目沿用训练营统一的 `agent-study` 工程（Java 21 + Spring Boot 4.1 + Spring AI 2.0），Day15 代码位于独立包 `com.zero.ai.agentstudy.day15knowledgeplatform`，不修改任何此前课程。

### 7.1 前置依赖

- JDK 21+
- Maven 3.9+
- Docker & Docker Compose（用于 PostgreSQL 17 + pgvector、Redis 8）
- 一个 OpenAI 兼容的 API（用于 Embedding 与 LLM）

### 7.2 启动基础设施（详见 DEPLOY.md）

```bash
# 启动 PostgreSQL(pgvector) + Redis
docker compose -f docs/docker-compose.yml up -d
```

### 7.3 配置 API Key

在 `application.yml` 或环境变量中配置 OpenAI 兼容端点与 Key（不要把密钥写进代码，使用环境变量）。

### 7.4 运行

```bash
mvn spring-boot:run
```

启动后即可通过 `knowledge-api` 暴露的接口上传文档、发起检索。完整接口见 `docs/API.md`。

---

## 八、最佳实践（Best Practices）

以下是从生产项目中沉淀的、贯穿整个 Day15 的工程准则：

1. **一切带租户上下文**：任何查询、任何写入，第一件事就是校验并注入 `tenant_id`。租户隔离是 SaaS 的生命线。
2. **权限前置到检索**：ACL 过滤发生在召回阶段（SQL/向量查询的 where 条件里），而不是在返回结果后再过滤。后置过滤既低效又危险（Top-K 可能全被过滤空）。
3. **Hybrid + Rerank 是默认检索策略**：不要只用向量。生产环境永远 Vector + BM25 + Rerank。
4. **管线化一切处理过程**：Parser → Chunk → Metadata → Embedding 做成可编排、可替换、可测试的 Pipeline。
5. **Chunk 大小要可配置且带重叠**：没有万能的 Chunk 大小，按文档类型调参，并保留 overlap 防止语义被切断。
6. **删除必须同步**：源删了，Chunk 和向量必须一起删。用事件驱动保证最终一致。
7. **元数据即治理**：尽可能丰富 Metadata（部门、标签、有效期、来源），检索时能过滤、能审计、能溯源。
8. **可观测性从第一天就加**：每个管线阶段埋 Micrometer 指标 + OpenTelemetry Trace，出问题能定位到具体环节。
9. **Embedding 模型要锁版本**：换 Embedding 模型必须全量重建向量，否则新旧向量不在同一空间，检索必然崩坏（向量漂移）。
10. **用 Virtual Threads 处理 I/O 密集的管线**：文档解析、Embedding 调用都是 I/O 密集，Java 21 虚拟线程能极大提升吞吐。

---

## 九、企业真实案例

- **客服知识库**：某电商把商品 FAQ、退换货政策、活动规则接入平台，客服 Agent 自动应答，人工客服工单下降 60%。关键点：知识**有效期**（活动规则会过期）+ **权限**（内部话术不能透出给客户）。
- **研发知识库**：某科技公司把 API 文档、架构决策、故障复盘接入平台，新人 onboarding 从「问遍全组」变成「问 Agent」。关键点：**代码/型号的精确检索**依赖 BM25，纯向量会漏。
- **法律知识库**：某律所把合同模板、法条、判例接入平台，律师检索特定条款。关键点：**极严格的 ACL**（不同客户的合同互相不可见）+ **溯源**（答案必须能指回原文段落）。
- **金融知识库**：某银行把合规手册、产品说明书接入平台，客户经理合规问答。关键点：**审计**（每次问答留痕）+ **多租户**（分行隔离）。
- **运维知识库**：某公司把 SOP、告警处置手册接入平台，值班 Agent 辅助处置。关键点：**增量同步**（手册频繁更新）。

---

## 十、避坑指南（企业知识平台十大坑）

1. **Chunk 太大**：一个 Chunk 塞进整页，向量被稀释，检索命中率下降、答案抓不住重点。
2. **Chunk 太小**：切得太碎，语义被割裂，检索到的片段缺乏上下文，答案支离破碎。
3. **Embedding 更新不重建**：换了 Embedding 模型却没全量重算，新旧向量混在一起，检索彻底失准（向量漂移）。
4. **权限泄漏**：ACL 后置过滤或压根没做，A 部门检索到 B 部门机密——最严重的合规事故。
5. **Metadata 缺失**：没记录来源/部门/时间，无法过滤、无法审计、无法溯源。
6. **删除不同步**：源文档删了，向量还在，检索出「幽灵知识」，甚至泄漏已被删除的敏感内容。
7. **只用向量不用 BM25**：搜产品型号、代码、专有名词大量漏召回。
8. **不做 Reranker**：Top-K 混入大量弱相关片段，LLM 被噪声干扰，答案质量差。
9. **忽视召回率监控**：没有指标，检索质量下降了都不知道，用户默默流失。
10. **同步阻塞主流程**：文档上传时同步做完解析+Embedding，接口超时。应异步化 + 事件驱动 + 状态机。
11.（附赠）**忽视成本**：Reranker、Embedding 大量调用推高成本，需加缓存、批处理、分级检索（先粗排再精排）。

---

## 十一、课后总结

今天我们完成了从「RAG Demo」到「Enterprise AI Knowledge Platform」的认知跃迁：

- 理清了企业知识管理的**五代演进史**，明白了知识平台是 AI 时代企业软件的**入口和地基**。
- 论证了**为什么 RAG 只是知识平台的一部分**，知识平台 80% 的工程量在检索之外。
- 分析了 **9 家业界标杆**，提炼出五条设计主线：Hybrid 检索、Reranker、权限感知、管线化、多数据源。
- 建立了**完整的概念地图**：Tenant → Workspace → KnowledgeBase → Document → Chunk → Embedding。
- 规划了 **14 个模块**及其严格单向依赖关系。
- 掌握了 **10 条最佳实践**与 **10+ 个经典坑**。

**下一步（第 1 章之后）**：我们将逐模块动手实现，从 `knowledge-core` 的领域模型开始，一路建到统一 API 与各 Agent 联动，最终集成进 ZeroHub AI Agent Platform。

---

## 十二、章节导航与当前进度

| 章节 | 主题 | 配套代码 / Demo | 状态 |
| --- | --- | --- | --- |
| 第 1 章 | knowledge-core 领域内核 | `core/` | ✅ |
| 第 2 章 | 整体架构设计：从 14 个模块看清全局 | `docs/ARCHITECTURE.md` | ✅ |
| 第 3 章 | knowledge-document：文档聚合与生命周期状态机 | `document/` · `Chapter03Demo` | ✅ |
| 第 4 章 | knowledge-parser：多格式解析与 OCR 兜底 | `parser/` · `Chapter04Demo` | ✅ |
| 第 5 章 | knowledge-chunk：三种切分策略与 Chunk 装配 | `chunk/` · `Chapter05Demo` | ✅ |
| 第 6 章 | knowledge-embedding：向量化与「模型锁版本」铁律 | `embedding/` · `Chapter06Demo` | ✅ |
| 第 7 章 | knowledge-retriever：Hybrid Search 与 RRF 融合 | `retriever/` · `Chapter07Demo` | ✅ |
| 第 8 章 | knowledge-reranker：精排与 MMR 多样性去重 | `reranker/` · `Chapter08Demo` | ✅ |
| 第 9 章 | knowledge-workspace：租户、工作空间与知识库 | `workspace/` · `Chapter09Demo` | ✅ |
| 第 10 章 | knowledge-acl：权限感知检索 | `acl/` · `Chapter09Demo`（第 10 章部分） | ✅ |
| 第 11 章 | knowledge-sync：增量同步与删除同步 | `sync/` · `Chapter11Demo` | ✅ |
| 第 12 章 | knowledge-browser：网页采集与连接器生态 | `browser/` · `Chapter11Demo`（第 12 章部分） | ✅ |
| 第 13 章 | knowledge-api：统一 API 与门面模式 | `api/`、`application/` · `Chapter13Demo` | ✅ |
| 第 14 章 | knowledge-admin：管理端与全链路复盘 | `admin/` · `Chapter14Demo` | ✅ |

### 如何运行全部 Demo

全部代码**零外部依赖**（不需要 PostgreSQL / Redis / Docker / API Key），编译后直接跑：

```bash
mvn -o compile

CP="target/classes:<poi/pdfbox/jackson 依赖>"
for d in Chapter03Demo Chapter04Demo Chapter05Demo Chapter06Demo Chapter07Demo \
         Chapter08Demo Chapter09Demo Chapter11Demo Chapter13Demo Chapter14Demo \
         IngestionPipelineDemo; do
  java -cp "$CP" com.zero.ai.agentstudy.day15knowledgeplatform.demo.$d
done
```

各章 Demo 覆盖的能力：

| Demo | 验证内容 |
| --- | --- |
| `Chapter03Demo` | 文档状态机拦截、跨租户防护、版本递增、删除事件 |
| `Chapter04Demo` | **凭空生成** docx/xlsx/pptx/pdf 再解析回来，闭环验证解析链路 |
| `Chapter05Demo` | 定长 / 语义 / 结构化三种切分策略的效果对比 |
| `Chapter06Demo` | 向量相似、归一化、批处理与缓存、**跨模型比较拦截** |
| `Chapter07Demo` | 向量 vs BM25 vs RRF 融合、租户隔离、**向量无零命中** |
| `Chapter08Demo` | 基线 vs 精排、MMR 去重、精排服务降级 |
| `Chapter09Demo` | 三层结构、跨租户拦截、ACL 矩阵、**职责分离** |
| `Chapter11Demo` | 内容指纹、增量同步计划（含 DELETE）、网页采集失败路径 |
| `Chapter13Demo` | 入库全链路、权限隔离、**删除同步**、**更新不同步** |
| `Chapter14Demo` | 授权审计、平台统计、**索引一致性检查**、全链路复盘 |
| `IngestionPipelineDemo` | 「上传 → 解析 → 切分」上半段流水线 |

### 当前实现约束（阅读代码前必读）

为保证「零依赖即可跑通」，本模块做了如下取舍，**这些都不是生产实现**：

- **存储全部为内存**：所有 Repository / VectorStore / BM25 索引均为内存实现，进程重启即丢失。
- **pgvector / Redis 未接入**：各仓储端口已留好，替换实现只需改 `PlatformConfiguration` 一处。
- **OCR 为 Null Object**：`NullOcrEngine` 明确报错而非静默返回空文本。
- **Embedding 默认是 HashingEmbeddingModel**：它是**字面相似**而非语义相似（第 6 章有详细说明），
  生产必须换成真实稠密模型。
- **租户与身份来自请求头**：仅用于演示，可被伪造；生产必须改用 JWT / OAuth2。
- **无 src/test 单元测试**：全仓库零测试，各章 Demo 承担了验证职责。

完整的「教学实现 vs 生产实现」差距清单，见 **第 14 章第六节**。

---

## 十三、配套文档索引

- `ARCHITECTURE.md`：整体架构、全链路 ASCII 图、如何支撑百万文档/SaaS/多租户。 ✅
- `chapters/`：每章学习总结、代码解析、面试题、扩展阅读。 ✅（已产出第 1~5 章）
- `API.md`：知识库/上传/检索/Workspace/ACL/Embedding/管理接口 + OpenAPI。 🔲 待产出
- `DATABASE.md`：全部表结构、索引设计、ER 图、优化。 🔲 待产出
- `DEPLOY.md`：Docker / Compose / K8s / 高可用 / 配置管理。 🔲 待产出
- `TESTING.md`：单测 / 集成测试 / Testcontainers / 性能测试。 🔲 待产出
- `adr/`：架构决策记录（为什么 Modulith / pgvector / Hybrid / Rerank / DDD）。 🔲 待产出
- `ROADMAP.md`：V1 / V2 / V3 SaaS 演进路线。 🔲 待产出
- `TODO.md`：⭐ 必做 / ⭐⭐ 进阶 / ⭐⭐⭐ 企业挑战。 🔲 待产出
- `CHANGELOG.md`：变更记录。 🔲 待产出

---

> **当前进度：第 0~14 章全部完成**，13 个业务模块全部实现并有可运行 Demo。
>
> 完整的文档处理与检索链路均可离线跑通：
>
> ```
> 上传 → 解析 → 切分 → 向量化 → 双索引入库 → 权限收敛 → 混合召回 → 精排 → 去冗余 → 结果
> ```
>
> 接下来可做的三件事：
>
> 1. **补齐配套文档**：`API.md` / `DATABASE.md` / `DEPLOY.md` / `TESTING.md` /
>    `adr/` / `ROADMAP.md` / `TODO.md` 仍未产出。
> 2. **把教学实现推向生产**：按第 14 章第六节的 10 条清单逐项替换
>    （优先级：租户来源 → 持久化与索引 → 真实 Embedding + minScore）。
> 3. **进入 Day16**：知识平台进阶（Hybrid 检索调优与评测体系）。
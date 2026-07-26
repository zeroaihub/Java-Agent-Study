# Day4：Memory —— 让 AI Agent 拥有长期记忆

> 《30 天打造商业级 AI Agent 平台（Java 版）》· Day4
>
> 导师：拥有 20 年经验的 AI Agent 首席架构师 · 面向从 Java/Spring 转型 AI Agent 工程师的你
>
> 一句话定位：**Day3 让 Agent「会做事」，Day4 让 Agent「记得住」——没有 Memory 的 Agent 永远只能服务当前这一句话，有了 Memory 才能持续理解用户。**

---

## 一、今日学习目标

Day3 我们让 Agent 长出了手脚（Tool Calling）。但每次对话对 LLM 来说都是"初次见面"——它不记得用户叫什么、做什么、喜欢什么。

Day4 的目标是给 Agent 装上 **Memory（记忆系统）**，让它从"一问一答工具"升级为"持续服务用户的助手"，具体分三个维度：

### 1. 理论能力（脑子里要有地图）

学完你要能清晰讲出：

- **为什么 LLM 没有记忆**：HTTP 无状态 + API 每次独立计算。
- **Memory 四大分类**：Short-term / Long-term / Working / Semantic。
- **Chat Memory 成本问题**：聊天越久 → Prompt 越长 → Token 越贵。
- **控制策略**：滑动窗口、Token 裁剪、摘要压缩、重要信息抽取。
- **长期画像设计**：UserProfile 的字段、置信度、生命周期。
- **框架抽象**：Spring AI ChatMemory vs LangChain4j ChatMemory。
- **企业治理**：成本、隐私、生命周期、Memory 与 RAG 协同。

### 2. 工程能力（手上要能落地）

完成一个**具备短期记忆 + 长期画像 + 自动兴趣更新的 Memory Agent**，支持：

| 能力 | 说明 |
| --- | --- |
| 短期对话记忆 | 最近 10 轮聊天，Session 隔离 |
| 滑动窗口 | 超出窗口的旧消息自动丢弃 |
| 摘要压缩 | 旧对话压缩为短摘要，控制 Token |
| 长期用户画像 | 姓名、职业、技能、偏好、目标 |
| 自动兴趣抽取 | 从对话中提取兴趣并更新画像 |
| 基于记忆回答 | 结合画像调整回答风格和示例 |

### 3. 架构能力（心里要有分层）

理解企业级 Memory Agent 的分层架构：

```
用户请求(userId + sessionId + message)
 ↓
Controller（参数校验）
 ↓
MemoryAgentService（主编排）
 ↓
├── ChatMemoryStore（Redis：最近 10 轮）
├── UserProfileRepository（MySQL：长期画像）
├── ProfileExtractor（自动抽取兴趣/偏好）
└── PromptBuilder（融合 Memory 组装 Prompt）
 ↓
ChatModel（LLM 生成回答）
```

**核心思想：Memory 不是模型权重更新，而是应用层在下一次请求前把必要上下文重新注入 Prompt。**

---

## 二、Memory 体系全景

### 2.1 为什么 Memory 是 Agent 个性化的基础

一句话：**没有 Memory 的 Agent 是"陌生人服务"，有了 Memory 才是"专属助手"。**

LLM 天然无状态：

- **不记得上一轮**：每次 API 调用都是独立计算。
- **不知道用户是谁**：不会自动记住姓名、职业、偏好。
- **不会长期学习**：不会因为聊了 100 轮就更懂你。

Memory 正是补齐"连续上下文能力"的应用层机制。

### 2.2 Memory 四大分类

```
Memory
├── Short-term Memory   短期记忆：最近几轮对话（Redis + TTL）
├── Long-term Memory    长期记忆：用户画像、偏好、目标（MySQL）
├── Working Memory      工作记忆：当前任务临时状态（任务结束即销毁）
└── Semantic Memory     语义记忆：抽象知识、事实（RAG / 向量库）
```

### 2.3 核心概念速查表

| 概念 | 是什么 | Java 工程师类比 |
| --- | --- | --- |
| Chat Memory | 保存多轮对话并注入 Prompt | HttpSession |
| Session ID | 隔离不同用户/会话 | JSESSIONID |
| 滑动窗口 | 只保留最近 N 条消息 | 固定大小队列 |
| UserProfile | 长期用户画像 | 数据库用户表 |
| 置信度 | 画像字段可信程度 | 数据校验通过率 |
| 摘要压缩 | 旧对话总结成短文本 | 日志归档压缩 |
| conversationId | userId:sessionId 组合 | 复合主键 |

---

## 三、Chat Memory 工作原理

### 3.1 为什么聊天越久越贵

```
第 1 轮：System + User1                              → 100 tokens
第 2 轮：System + User1 + Assistant1 + User2         → 300 tokens
第 3 轮：System + User1 + A1 + U2 + A2 + U3         → 600 tokens
...
第 N 轮：全部历史累积                                → 成本线性增长
```

API 不会"只计算新增消息"，每次都重新读取全部上下文。

### 3.2 四种控制策略

| 策略 | 原理 | 适用场景 |
| --- | --- | --- |
| 固定窗口 | 只保留最近 N 条 | 简单客服 |
| Token 裁剪 | 控制最大上下文 Token | 成本敏感 |
| 摘要压缩 | 旧对话 → 短摘要 | 长会话助手 |
| 信息抽取 | 关键信息 → UserProfile | 个性化服务 |

---

## 四、企业级 Memory 治理要点

### 4.1 四大治理维度

```
① 成本治理：压缩、裁剪、缓存，控制 Token 消耗
② 生命周期：创建 → 更新 → 过期 → 删除
③ 画像策略：抽取 → 确认 → 合并 → 回滚
④ 协同机制：Memory（用户是谁）+ RAG（知识是什么）+ Tool（执行什么）
```

### 4.2 事实优先级

```
系统规则 > RAG 文档 > Tool 实时结果 > 用户 Memory > 模型常识
```

---

## 五、实战项目介绍：Memory Agent

技术栈：**Java 21 + Spring Boot 4.1.0 + Spring AI 2.0 GA**。

对外提供的接口（第八章最终版）：

| 接口 | 方法 | 作用 |
| --- | --- | --- |
| `/api/day4/chapter8/chat` | POST | 带记忆的对话 |
| `/api/day4/chapter8/inspect` | GET | 查看当前记忆状态 |
| `/api/day4/chapter8/clear-session` | DELETE | 清除会话记忆 |

代码资产（按章节递进）：

- chapter1：最简 Memory 对比（有/无记忆）
- chapter2：Memory 分类模型
- chapter3：Chat Memory 策略（窗口/压缩）
- chapter4：UserProfile 长期画像
- chapter5：框架 Memory 抽象
- chapter6：完整 Memory Agent（Redis + MySQL 模拟）
- chapter7：企业最佳实践（生命周期/协同）
- chapter8：最终 Memory Agent（收官作品）

---

## 六、面试重点（Day4 高频考点）

1. **为什么 LLM 没有长期记忆？** —— HTTP 无状态，API 每次独立计算。
2. **Memory 的本质是什么？** —— 应用层保存上下文，请求前注入 Prompt。
3. **Memory 分哪几类？** —— Short-term / Long-term / Working / Semantic。
4. **为什么聊天越久越贵？** —— 历史消息累积，每次全量发送。
5. **如何控制 Chat Memory 成本？** —— 窗口、Token 裁剪、摘要压缩、信息抽取。
6. **长期画像该存什么？** —— 稳定身份/能力/偏好/目标，不存敏感信息。
7. **conversationId 怎么设计？** —— userId:sessionId 组合隔离。
8. **Memory 和 RAG 的区别？** —— Memory 存用户信息，RAG 存业务知识。
9. **生产环境 Memory 用什么存储？** —— 短期 Redis + TTL，长期 MySQL/PostgreSQL。
10. **画像更新要注意什么？** —— 置信度、去重、可删除、可审计。

---

## 七、今日章节安排（严格串行，逐章暂停）

| 章节 | 主题 | 完成后 |
| --- | --- | --- |
| 第一章 | 为什么 Agent 必须有 Memory | 暂停，回答「为什么普通聊天机器人没有长期记忆？」 |
| 第二章 | Memory 分类 | 暂停，画出 Memory 分类图 |
| 第三章 | Chat Memory 工作原理 | 暂停，解释「为什么聊天越久越贵？」 |
| 第四章 | 长期 Memory 设计 | 暂停，设计企业级 UserProfile |
| 第五章 | Spring AI 与 LangChain4j 中的 Memory | 暂停，对比两大框架 |
| 第六章 | Java 实现 Memory Agent | 完成 Redis + MySQL + 兴趣更新 |
| 第七章 | 企业最佳实践 | 设计 Memory 生命周期 |
| 第八章 | 完成 Memory Agent | 整合成完整 Agent 作品 |

> **学习原则：一次只讲一章，讲完暂停，等你确认并回答章末问题后，再进入下一章。**

---

## 八、教学框架（每章五段式）

| 段落 | 目标 | 输出 |
|------|------|------|
| ① 为什么学（核心价值） | 解释技术解决什么痛点 | 建立商业价值判断 |
| ② 是什么（概念与原理） | 定义 + 核心流程 | 建立准确心智模型 |
| ③ 怎么用（实战演练） | Java 优先，Python 辅助 | 从看懂到能写 |
| ④ 用在哪（应用场景） | 结合真实 Agent 项目 | 理解落地场景 |
| ⑤ 避坑与优化（进阶提升） | 常见问题、性能、工程化 | 接近企业级规范 |

---

## 九、目录约定

- 本日所有代码与文档独立于前几日，禁止修改既有学习代码。
- Java 包名：`com.zero.ai.agentstudy.day4memory`
- 代码按章节组织：`chapter1/` ~ `chapter8/`
- 每章采用"五段式"结构，每完成一章在对应目录下归档

现在，请从 **第一章：为什么 Agent 必须有 Memory** 开始学习。

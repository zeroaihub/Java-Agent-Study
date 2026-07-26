# Day4 架构说明：Memory Agent

> 本文档描述 Day4「Memory / 长期记忆」落地代码的整体架构、分层职责、请求流程与扩展方向。
> 所有代码位于独立包 `com.zero.ai.agentstudy.day4memory`，基于 Java 21 + Spring Boot 4.1.0 + Spring AI 2.0 GA，可独立运行。

---

## 一、分层架构总览

```
HTTP 请求(userId + sessionId + message)
   │
   ▼
Controller 层  (各章 Controller)
   │  参数校验、路由分发
   ▼
Service 编排层
   ├─ MemoryAgentService       主编排：读记忆 → 组装 Prompt → 调 LLM → 存记忆 → 更新画像
   ├─ ProfileExtractor         从对话中自动抽取兴趣/偏好/目标
   └─ PromptBuilder            融合短期记忆 + 长期画像 + 用户问题
   ▼
存储层
   ├─ ChatMemoryStore          短期记忆（Redis 模拟：最近 10 轮 + TTL）
   └─ UserProfileRepository    长期画像（MySQL 模拟：置信度 + 更新时间）
   ▼
Spring AI ChatClient ──► 大模型（OpenAI 兼容接口）
```

核心机制：Memory 不是模型权重更新，而是应用层在每次请求前，把必要的短期对话 + 长期画像重新注入 Prompt，让无状态 LLM 具备连续上下文能力。

---

## 二、各层职责

| 层 | 类 | 职责 |
|----|----|----|
| Controller | `Chapter1~8 Controller` | 按章节暴露测试入口，参数校验 |
| Service | `MemoryAgentService` | 主编排：读记忆 → 组装 Prompt → 调 LLM → 存记忆 → 更新画像 |
| 抽取器 | `ProfileExtractor` | 从用户消息中抽取兴趣、偏好、目标等信号 |
| Prompt | `PromptBuilder` | 融合 UserProfile + 最近对话 + 当前问题，生成结构化 Prompt |
| 短期存储 | `ChatMemoryStore` | 按 conversationId 保存最近 N 轮，超限自动裁剪 |
| 长期存储 | `UserProfileRepository` | 按 userId 保存/更新/删除画像，含置信度和更新时间 |

---

## 三、八章递进与能力映射

| 章节 | 入口前缀 | 演示能力 |
|------|----------|----------|
| chapter1 | `/api/day4/chapter1/` | 有/无 Memory 对比，Session 隔离 |
| chapter2 | `/api/day4/chapter2/` | Memory 四大分类模型，自动归类 |
| chapter3 | `/api/day4/chapter3/` | Chat Memory 策略：完整历史/滑动窗口/摘要压缩 |
| chapter4 | `/api/day4/chapter4/` | UserProfile 长期画像 CRUD + 字段决策 |
| chapter5 | `/api/day4/chapter5/` | 框架 Memory 抽象（模拟 Spring AI / LangChain4j） |
| chapter6 | `/api/day4/chapter6/` | 完整 Memory Agent（Redis + MySQL 模拟 + 兴趣更新） |
| chapter7 | `/api/day4/chapter7/` | 企业最佳实践（生命周期/压缩决策/RAG 协同） |
| chapter8 | `/api/day4/chapter8/` | 最终 Memory Agent（收官作品） |

---

## 四、请求流程（最终版 /api/day4/chapter8/chat）

```
Client
  │ POST /api/day4/chapter8/chat
  │ Body: {"userId":"u1","sessionId":"s1","message":"我叫张三，是 Java 工程师"}
  ▼
FinalMemoryAgentController
  │ 参数校验
  ▼
FinalMemoryAgentService.chat(userId, sessionId, message)
  ├─ 1. 构造 conversationId = "u1:s1"
  ├─ 2. 读取 ChatMemoryStore 最近 10 轮
  ├─ 3. 读取 UserProfileRepository 长期画像
  ├─ 4. PromptBuilder 组装：画像 + 最近对话 + 当前问题
  ├─ 5. 调用 ChatClient 生成回答
  ├─ 6. 保存 user/assistant 消息到 ChatMemoryStore
  ├─ 7. ProfileExtractor 抽取兴趣/偏好 → 更新 UserProfile
  └─ 返回回答 + conversationId
```

---

## 五、Memory 存储策略

| 类型 | 存储 | 生命周期 | 说明 |
|------|------|----------|------|
| 短期对话 | Redis（List + TTL） | 24h 或会话结束 | 最近 10 轮，超限裁剪 |
| 历史摘要 | MySQL | 90 天 | 旧对话压缩后的摘要 |
| 长期画像 | MySQL | 长期（用户可删除） | 姓名/职业/技能/偏好/目标 |
| 工作记忆 | 内存 | 任务结束即销毁 | 当前任务临时状态 |
| 语义知识 | 向量库（RAG） | 随知识库更新 | 业务文档、事实 |

---

## 六、UserProfile 设计原则

1. **只存对未来服务有价值的信息**：身份、能力、偏好、目标。
2. **不存敏感信息**：密码、token、身份证、银行卡默认不入库。
3. **必须有置信度**：推测信息 < 0.7 不注入 Prompt。
4. **必须有更新时间**：过期画像降权或提示确认。
5. **支持用户删除**：符合隐私合规（GDPR 等）。
6. **更新需去重合并**：多次提到同一兴趣不重复添加。

---

## 七、Prompt 组装结构

```
你是企业级 AI Agent。

【长期用户画像】
姓名：张三 | 职业：Java 后端工程师 | 目标：成为 AI Agent 架构师
偏好：Java 优先、需要企业案例

【历史对话摘要】
用户之前在学习 Memory 分类和 Chat Memory 工作原理。

【最近对话】
user: ...
assistant: ...

【用户当前问题】
...

请优先基于用户画像调整表达方式，结合最近对话上下文回答。
```

---

## 八、扩展方向（Demo → 生产级）

- **真实 Redis**：将内存模拟替换为 Spring Data Redis，List + TTL + LTRIM 裁剪。
- **真实 MySQL**：将内存 Map 替换为 JPA/MyBatis，支持持久化和并发安全。
- **LLM 抽取画像**：用结构化输出（JSON Schema）让 LLM 自动抽取 UserProfile 字段。
- **摘要压缩**：旧对话超阈值时调 LLM 生成摘要，替代简单丢弃。
- **RAG 协同**：PromptBuilder 预留 `ragContext` 插槽，接入 Day5 向量检索。
- **可观测**：Memory 读写增加 traceId + Micrometer 指标（命中率/延迟）。
- **隐私合规**：用户画像查看/编辑/删除 API，操作审计日志。
- **多实例部署**：短期 Memory 必须用共享存储（Redis），不能依赖本地内存。

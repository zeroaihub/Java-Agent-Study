# 架构决策记录（ADR）

> ADR（Architecture Decision Record）记录项目关键架构决策的**背景、选项、结论与后果**，
> 让后来者理解「为什么这样设计」，避免重复讨论已定论的问题。

## 什么是 ADR

每条 ADR 是一份不可变的记录（一旦定稿不改，若要推翻则新增一条并标记 supersedes）。
格式：**标题 / 状态 / 背景 / 决策 / 后果 / 备选方案**。

## 状态说明

- `Proposed` 提议中
- `Accepted` 已采纳
- `Superseded` 被后续 ADR 取代
- `Deprecated` 已废弃

## 记录索引

| 编号 | 标题 | 状态 |
| --- | --- | --- |
| [ADR-001](ADR-001-hexagonal-architecture.md) | 采用六边形架构 + DDD | Accepted |
| [ADR-002](ADR-002-orchestration-over-choreography.md) | 编排而非编舞 | Accepted |
| [ADR-003](ADR-003-standalone-agent-module.md) | 独立 wechatagent 编排模块 | Accepted |

---

> 新增决策时：复制模板 → 递增编号 → 填写六个小节 → 更新本索引表。
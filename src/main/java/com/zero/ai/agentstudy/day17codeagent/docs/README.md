# Day17 · Code Agent（代码生成与执行）

> 从「能聊天的 LLM」到「能改代码、自己验证、可被信任的工程角色」。
>
> 本项目是 ZeroHub AI Agent Platform 的**编码智能核心**，在 Day15「企业知识平台」与 Day16「企业知识智能体」之上，把 LLM 推进到真正能落地的 **Code Agent**。

---

## 目录

1. [项目介绍](#一项目介绍)
2. [为什么需要 Code Agent](#二为什么需要-code-agent)
3. [核心能力](#三核心能力20-项)
4. [系统架构](#四系统架构)
5. [模块划分](#五模块划分)
6. [技术栈](#六技术栈)
7. [快速启动](#七快速启动)
8. [配置说明](#八配置说明)
9. [数据库设计](#九数据库设计)
10. [API 概览](#十api-概览)
11. [测试策略](#十一测试策略)
12. [部署](#十二部署)
13. [监控与可观测性](#十三监控与可观测性)
14. [企业案例](#十四企业案例)
15. [性能优化](#十五性能优化)
16. [安全](#十六安全)
17. [商业化方向](#十七商业化方向)
18. [课程章节导航](#十八课程章节导航)

---

## 一、项目介绍

**Code Agent** 是一个能在受限环境里「读仓库 → 理解需求 → 生成补丁 → 自己编译验证 → 自己跑测试 → 自己修错误 → 产出可审阅 PR」的完整智能体。

让 LLM 写代码和让 LLM *交付* 代码，中间隔着一条巨大的鸿沟。一个只会「输出代码片段」的模型，在真实工程里会立刻暴露十个问题：

| 现象 | 根因 |
| --- | --- |
| 生成的代码根本编译不过 | 没有验证闭环 |
| 改对了语法，改错了语义 | 只有编译没有测试 |
| 一次重写整个文件，顺手改掉无关代码 | 没有「最小改动」约束 |
| 补丁打不上（凭记忆写旧代码） | 上下文给的是模型记忆而非仓库现状 |
| 仓库太大塞不进上下文 | 没有上下文预算与选择 |
| 它想改 CI 配置 / 加了密钥 | 没有落盘前的安全闸门 |
| 死循环把机器跑挂 | 没有沙箱与超时 |
| 循环重试一整晚，烧掉几千块 | 没有预算护栏与无进展检测 |
| PR 里只有一句「已修复」 | 没有变更集与 PR 生成 |
| 出了问题不知道是哪一步坏的 | 没有全链路 Trace |

Day17 的使命，就是把这十个问题逐条解决，构建出一个**能被工程师信任**的 Code Agent。

---

## 二、为什么需要 Code Agent

| 维度 | 代码补全工具 | Code Agent |
| --- | --- | --- |
| 输入 | 光标上下文 | 整个仓库 + 自然语言需求 |
| 输出 | 代码片段 | 可应用补丁 + 验证结果 + PR |
| 验证 | 无 | 编译 + 测试 + 规则评审 |
| 自愈 | 无 | 编译失败自动反馈重修 |
| 权限 | 无 | 租户 / 角色 / 路径 / 命令四层护栏 |
| 成本 | 单次 | 预算护栏 + 无进展早停 |
| 可追溯 | 无 | 全链路 Trace + 变更集 |

**商业价值**：Code Agent 是 GitHub Copilot Workspace、Devin、Cursor Agent、通义灵码这类产品的核心引擎。它把「写代码」从按小时计费的人力劳动，变成可被度量、可被审计、可被批量执行的工程流水线。

---

## 三、核心能力（20 项）

| # | 能力 | 说明 | 章节 |
| --- | --- | --- | --- |
| 1 | Repository Snapshot | 仓库快照与内容指纹 | Ch03 |
| 2 | 虚拟文件系统（VFS） | 路径越界防护，唯一写入口 | Ch03 |
| 3 | Tool Registry | 工具注册与动态工具清单 | Ch04 |
| 4 | 失败即返回值 | 工具失败不外抛异常 | Ch04 |
| 5 | 静态分析 / RepoMap | 零依赖符号提取，索引卡式仓库地图 | Ch05 |
| 6 | Context Budget | 上下文预算与降级（全文→签名） | Ch06 |
| 7 | SEARCH/REPLACE 补丁 | 最小改动面 | Ch07 |
| 8 | 补丁解析 | 从脏输出里解析补丁 | Ch08 |
| 9 | 代码生成策略 | 整文件重写 vs 增量补丁 | Ch09 |
| 10 | 沙箱执行 | 白名单 / 超时 / 环境隔离 | Ch10 |
| 11 | 编译验证 | 真调 javac，结构化诊断 | Ch11 |
| 12 | 测试验证 | 编译通过 ≠ 正确 | Ch12 |
| 13 | 自修复循环 | 三重刹车：轮次 / 无进展 / 指纹 | Ch13 |
| 14 | 安全护栏 | 路径黑名单 / 危险 API / 密钥扫描 | Ch14 |
| 15 | 规则式代码评审 | 只评审增量，能规则化的不交给 LLM | Ch15 |
| 16 | 测试生成 | 生成→编译→运行红绿闭环 | Ch16 |
| 17 | GitOps | 变更集 + 风险分级 + PR 草稿 | Ch17 |
| 18 | 评测 | Pass@k / 四个指标分离归因 | Ch18 |
| 19 | 可观测与计量 | 步骤 Trace + 预算护栏 | Ch19 |
| 20 | 全链路编排 | Plan-Act-Observe 主循环 + API | Ch20 |

---

## 四、系统架构

```
                  需求（自然语言）
                        │
                        ▼
              上下文选择（符号 + 依赖 + 预算）
                        │
                        ▼
              PromptAssembler（角色 + 工具 + RepoMap + 选中代码 + 输出契约）
                        │
                        ▼
                    CodeModel（LLM）
                        │
                        ▼
               PatchParser（SEARCH/REPLACE 或 Unified Diff）
                        │
                        ▼
            ┌── PatchSafetyChecker ── SecretScanner ──┐
            │        （落盘前拒绝，不浪费后续验证）      │
            └───────────────┬───────────────────────┘
                            ▼
                     PatchApplier → VFS
                            │
                            ▼
                 CompileVerifier（javax.tools 真编译）
                            │
                     失败 ──┴── 成功
                      │           │
              SelfRepairLoop      TestVerifier（沙箱内跑测试）
              （最多 N 轮）              │
                      │                 │
                      └──── 反馈 ───────┤
                                        ▼
                             RuleBasedReviewer（只评审增量）
                                        ▼
                      ChangeBundle → 风险分级 → PullRequestDraft
                                        │
                                        ▼
                       TaskTrace（每步耗时/token）+ BudgetGuard
```

详细数据流 / 安全流 / 验证流见 [ARCHITECTURE.md](./ARCHITECTURE.md)。

---

## 五、模块划分

采用 DDD + Hexagonal，依赖方向严格单向（越底层越稳定）：

```
api  →  orchestrator  →  iterate  →  verify  →  sandbox
                     ↘  gitops   ↘  patch   ↘  safety
                     ↘  context  →  analysis →  repo
                     ↘  eval / observability / metering / review / testgen
                                 ↘  core（零依赖内核）
```

| 模块 | 职责 | 为什么这样划分 |
| --- | --- | --- |
| `core` | 强类型 ID、`CodeTaskContext`、统一结果/异常/错误码 | 零框架依赖，最稳定 |
| `repo` | VFS + 快照 + 内容指纹 | 路径安全必须只有一个入口 |
| `tools` | 工具端口、注册表、五个工具 | 工具集需要动态拼进提示词 |
| `analysis` | 轻量解析器、符号表、依赖图、RepoMap | 代码理解独立于生成 |
| `context` | 预算 + 选择器 + 降级策略 | 上下文是稀缺资源，需要独立治理 |
| `patch` | 补丁表示、解析、应用、diff | 落盘动作的收敛点 |
| `generator` | 模型端口 + 提示词装配 | 唯一知道 LLM 存在的模块 |
| `sandbox` | 进程隔离执行 | 安全边界必须独立 |
| `verify` | 编译 + 测试验证 | 验证是信任的来源 |
| `iterate` | 自修复循环 + 三重刹车 | 控制成本的关键 |
| `safety` | 路径 / 命令 / 密钥扫描 | 落盘前闸门 |
| `review` | 规则式评审 | 确定性问题不交给模型 |
| `testgen` | 测试生成 | 让 Agent 自己证明自己 |
| `gitops` | 变更集 + PR 草稿 | 对人类交付的界面 |
| `eval` | 评测与 Pass@k | 质量可度量才能改进 |
| `observability` / `metering` | Trace + 预算 | 生产可运维 |
| `orchestrator` / `api` | 主循环 + 对外接口 | 应用层，依赖上述全部 |

> **划分原则**：业务模块零 Spring 注解，只在 `api/CodeAgentConfiguration` 一处装配。换掉 Sandbox 不影响 Patch，换掉 LLM 不影响 Verify。

---

## 六、技术栈

- **语言/框架**：Java 21 LTS、Spring Boot 4.1.0、Spring AI 2.0.0、Maven
- **编译器接入**：`javax.tools.JavaCompiler`（JDK 自带，零第三方依赖）
- **存储**：PostgreSQL 17（任务 / 变更集 / Trace）、Redis 8（预算与配额）
- **模型接入**：OpenAI Compatible API
- **部署**：Docker / Kubernetes，沙箱建议跑在 gVisor / Kata 容器
- **可观测**：Micrometer + OpenTelemetry
- **测试**：JUnit 5 + Testcontainers
- **架构**：DDD + Clean/Hexagonal

---

## 七、快速启动

```bash
# 1. 编译（复用 agent-study 统一工程）
mvn -o clean compile

# 2. 运行任意一个章节 Demo（零依赖，可离线）
java -Dstdout.encoding=UTF-8 -cp target/classes \
     com.zero.ai.agentstudy.day17codeagent.orchestrator.Chapter20Demo

# 3. 完整链路（Ch20）会打印：
#    Trace 时间线 → 变更集 → PR 草稿 → 是否可合并
```

> **Windows 注意**：控制台默认 GBK，运行 Demo 请加 `-Dstdout.encoding=UTF-8`，否则中文输出乱码。

全部 18 个 Demo 均已在 JDK 21 上验证通过（退出码 0）。

---

## 八、配置说明

| 配置项 | 说明 | 默认 |
| --- | --- | --- |
| `code.sandbox.allowed-executables` | 沙箱可执行文件白名单 | `java, javac` |
| `code.sandbox.timeout-ms` | 沙箱超时 | `30000` |
| `code.sandbox.inherit-env` | 是否继承宿主环境变量 | `false` |
| `code.patch.max-removed-lines` | 单次补丁最大删除行数 | `200` |
| `code.repair.max-rounds` | 自修复最大轮次 | `3` |
| `code.repair.stop-on-no-progress` | 无进展时是否早停 | `true` |
| `code.budget.max-tokens` | 单任务 token 上限 | `20000` |
| `code.budget.max-model-calls` | 单任务模型调用上限 | `6` |
| `code.context.budget` | 上下文预算档位 | `32k` |

> 敏感配置（API Key）一律走环境变量 / Secret，禁止入库，也不得传入沙箱子进程。详见 [SECURITY.md](./SECURITY.md)。

---

## 九、数据库设计

核心表（详见 [DATABASE.md](./DATABASE.md)）：`code_task`、`patch_record`、`compile_record`、`test_record`、`repair_round`、`change_bundle`、`pr_draft`、`trace_step`、`budget_usage`、`eval_sample`。

关键设计：
- 所有业务表带 `tenant_id`，并建立 `(tenant_id, ...)` 复合索引，从数据库层强制租户隔离；
- `patch_record` 记录原始补丁文本与最终采用的匹配策略（精确 / 空白容错），供事后审计；
- `compile_record` 存结构化诊断（`file / line / column / code`），而不是一段原始日志；
- `trace_step` 存每步耗时与 token，是成本分析的唯一数据源。

---

## 十、API 概览

详见 [API.md](./API.md)。核心端点：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v2/code/tasks` | 提交编码任务（返回变更集 + 验证 + PR 草稿） |
| GET | `/api/v2/code/tasks/{taskId}` | 查询任务 |
| GET | `/api/v2/code/tasks/{taskId}/trace` | 查询链路追踪 |
| POST | `/api/v2/code/evaluate` | 触发评测流水线 |

请求体**不含** `tenantId` / `userId` —— 它们只能由网关通过 Header 注入。

---

## 十一、测试策略

Unit / Integration（Testcontainers）/ Patch Apply Test / Compile Test / Sandbox Escape Test / Permission Test / Eval Test / E2E。详见 [TESTING.md](./TESTING.md)。

**负向测试是重中之重**：路径穿越、命令白名单、预算拦截、无权限写入——这四类必须各有一组失败用例。

---

## 十二、部署

无状态设计，任务与 Trace 下沉 PostgreSQL，预算计数下沉 Redis。**沙箱必须跑在容器里**（gVisor / Kata），本模块的进程级沙箱只适用于教学与低风险场景。详见 [DEPLOY.md](./DEPLOY.md)。

---

## 十三、监控与可观测性

每一次任务产出一条 Trace，覆盖 8~10 个步骤，每步带耗时、token 与摘要：

```
上下文选择 → 提示词装配 → 模型调用 → 安全审查 → 补丁应用
          → 编译+自修复 → 规则评审 → 变更集+PR
```

`TaskTrace.bottleneck()` 直接给出瓶颈步骤。`BudgetGuard` 记录 token / 调用数 / 轮次 / 预计花费。

---

## 十四、企业案例

- **内部研发助手**：改 bug、加方法、补测试、出 PR；
- **批量重构**：升级依赖、统一日志、移除废弃 API（可并行数百个仓库）；
- **遗留系统现代化**：JDK 8 → 21、Spring Boot 2 → 4 的机械性迁移；
- **新同学上手**：问「下单流程在哪」→ 返回定位 + 影响面 + 依赖图。

对标 GitHub Copilot Workspace、Devin、Cursor Agent、Amazon Q Developer。

---

## 十五、性能优化

- 内存片段编译（34ms）vs 整仓库编译（754ms），自修复循环优先用前者；
- 符号表 / 依赖图预建，一次任务内多次复用；
- RepoMap 压缩：102 行源码 → 537 token 索引卡；
- 增量补丁比整文件重写省 53.9% 输出 token（Ch09 实测）；
- 无进展检测把 5 轮无效重试收敛到 1 轮（Ch13 实测）。

---

## 十六、安全

四层护栏：租户/角色 → 路径（VFS）→ 命令（沙箱白名单）→ 落盘前审查（危险 API + 密钥扫描）。
外加环境变量隔离，杜绝「Agent 生成的代码读走 `OPENAI_API_KEY`」。详见 [SECURITY.md](./SECURITY.md)。

---

## 十七、商业化方向

作为 ZeroHub AI Agent Platform 的编码底座，向下游提供「可信的代码变更服务」，按任务数 / token / 修复轮次计费。
真正的护城河不在模型，而在**验证闭环与审计能力**——客户愿意为「改了什么、怎么验证的、能不能回滚」付费。

---

## 十八、课程章节导航

| 章节 | 主题 | Demo |
| --- | --- | --- |
| [Ch01](./chapters/chapter-01.md) | 为什么需要 Code Agent + 总体架构 | — |
| [Ch02](./chapters/chapter-02.md) | 架构与模块划分 | — |
| [Ch03](./chapters/chapter-03.md) | 仓库快照与虚拟文件系统 | `repo.Chapter03Demo` |
| [Ch04](./chapters/chapter-04.md) | 工具集与注册表 | `tools.Chapter04Demo` |
| [Ch05](./chapters/chapter-05.md) | 静态分析与 RepoMap | `analysis.Chapter05Demo` |
| [Ch06](./chapters/chapter-06.md) | 上下文选择与预算裁剪 | `context.Chapter06Demo` |
| [Ch07](./chapters/chapter-07.md) | 补丁表示与应用 | `patch.Chapter07Demo` |
| [Ch08](./chapters/chapter-08.md) | 从 LLM 输出解析补丁 | `patch.Chapter08Demo` |
| [Ch09](./chapters/chapter-09.md) | 代码生成策略 | `generator.Chapter09Demo` |
| [Ch10](./chapters/chapter-10.md) | 代码沙箱 | `sandbox.Chapter10Demo` |
| [Ch11](./chapters/chapter-11.md) | 编译验证 | `verify.Chapter11Demo` |
| [Ch12](./chapters/chapter-12.md) | 测试验证 | `verify.Chapter12Demo` |
| [Ch13](./chapters/chapter-13.md) | 自修复循环 | `iterate.Chapter13Demo` |
| [Ch14](./chapters/chapter-14.md) | 安全护栏 | `safety.Chapter14Demo` |
| [Ch15](./chapters/chapter-15.md) | 代码评审 | `review.Chapter15Demo` |
| [Ch16](./chapters/chapter-16.md) | 测试生成 | `testgen.Chapter16Demo` |
| [Ch17](./chapters/chapter-17.md) | GitOps 变更管理 | `gitops.Chapter17Demo` |
| [Ch18](./chapters/chapter-18.md) | 评测与 Pass@k | `eval.Chapter18Demo` |
| [Ch19](./chapters/chapter-19.md) | 可观测性与成本计量 | `observability.Chapter19Demo` |
| [Ch20](./chapters/chapter-20.md) | 完整集成实战 | `orchestrator.Chapter20Demo` |

> 本模块采用**逐章教学**：每章对应关系清晰的包与一个可独立运行的 `ChapterXXDemo`。所有 Demo 零第三方依赖、可离线、输出确定。

---

## 当前实现状态（诚实交代）

1. **模型是桩**：`StubCodeModel` 按关键词模板输出补丁，不理解语义。真实能力需要换成 Spring AI 的 `ChatClient`（只需改 `CodeAgentConfiguration.codeModel()` 一处）。
2. **沙箱是进程级**：不是容器级隔离。生产应跑在 gVisor / Kata / Docker，并限制网络与挂载。
3. **测试是 assert + main**：不依赖 JUnit 运行时，为「零依赖跑通」刻意简化。生产应换 JUnit 5 + Surefire。
4. **静态分析是正则级**：不处理深度嵌套泛型、注解换行等复杂语法。生产应换 JavaParser 或 javac Trees API。
5. **零测试代码**：仓库尚无 `src/test`（与 Day01~Day16 现状一致），本章的「测试验证」验证的是 Agent 产出的测试，而非本模块自身的单测。
6. **未持久化**：任务、变更集、Trace 全在内存，进程重启即丢失。

生产化替换清单见 [chapter-20.md](./chapters/chapter-20.md) 第六节。

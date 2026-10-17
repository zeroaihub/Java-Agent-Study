# Chapter 02 · 总体架构与模块划分

> 本章目标：把 Day17 的 19 个包整理成一张可记忆的地图，理解**为什么**这样分层、依赖方向为什么必须严格单向，以及「零 Spring 注解 + 单一装配点」这条规则带来的实际收益。本章无新代码，是全局认知章。

---

## 一、为什么要学（Why）

Chapter 01 列出了十个问题，但「知道要做什么」和「知道代码该放哪」之间还差一次架构设计。两个真实教训：

**教训一：分层不清导致安全边界失效。**
如果把路径校验散落在「读文件工具」「写文件工具」「补丁应用器」三处，只要有一处漏判 `..`，整个仓库就门户大开。安全能力必须**收敛到一个模块**（`repo.VirtualFileSystem`），让别的地方根本没有机会绕过。

**教训二：依赖方向混乱导致换不动。**
如果 `patch` 直接依赖 `generator`（因为「生成完要打补丁」），那么换模型时就会连带改动补丁逻辑；如果 `verify` 依赖 `orchestrator`，测试就没法单独跑。**依赖必须指向稳定的方向**，变化才不会扩散。

架构不是画好看的图，而是**约束变化扩散的范围**。

---

## 二、是什么（What）

### 2.1 依赖方向：严格单向

```
api  →  orchestrator  →  iterate  →  verify  →  sandbox
                     ↘  gitops   ↘  patch   ↘  safety
                     ↘  context  →  analysis →  repo
                     ↘  eval / observability / metering / review / testgen
                                 ↘  core（零依赖内核）
```

读法：箭头表示「依赖」，越靠下越稳定。三条硬规则：

1. **`core` 谁都不依赖**：只放强类型 ID、上下文、统一结果/异常/错误码。
2. **能力模块之间不互相依赖**：`patch` 不认识 `generator`，`verify` 不认识 `iterate`。它们靠**接口**在编排层拼起来。
3. **只有 `api` 依赖 Spring**：业务模块零 Spring 注解。

### 2.2 19 个模块的职责边界

| 模块 | 职责 | 为什么这样划分 |
| --- | --- | --- |
| `core` | 强类型 ID、`CodeTaskContext`、统一结果/异常/错误码 | 零框架依赖，最稳定 |
| `repo` | VFS + 快照 + 内容指纹 | **路径安全必须只有一个入口** |
| `tools` | 工具端口、注册表、五个工具 | 工具集需要动态拼进提示词 |
| `analysis` | 轻量解析器、符号表、依赖图、RepoMap | 代码理解独立于生成 |
| `context` | 预算 + 选择器 + 降级策略 | 上下文是稀缺资源，需要独立治理 |
| `patch` | 补丁表示、解析、应用、diff | 落盘动作的收敛点 |
| `generator` | 模型端口 + 提示词装配 | **唯一知道 LLM 存在的模块** |
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

**一句话记忆**：`repo` 管「能写哪」，`analysis`+`context` 管「看什么」，`patch` 管「怎么改」，`sandbox`+`verify` 管「对不对」，`safety`+`review` 管「能不能过」，`gitops` 管「怎么交」，`observability`+`metering`+`eval` 管「好不好」。

### 2.3 主流程：一次任务流经的 12 步

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

两个顺序上的关键决策：

- **安全审查在应用补丁之前**：被拒的补丁不会浪费任何编译与测试资源，也不会在磁盘上留下半成品。
- **编译在测试之前**：编译是便宜且确定的检查，测试昂贵且慢。先跑便宜的，能通过就别浪费贵的。

---

## 三、怎么用（How）

### 3.1 端口与适配器：换实现不改调用方

以「模型」为例。`generator` 只暴露一个接口：

```java
public interface CodeModel {
    GenerationOutcome generate(GenerationRequest request);
    String modelId();
}
```

本课程用 `StubCodeModel`（按关键词模板返回补丁，不理解语义）跑通全链路；真实上线换成 Spring AI 的 `ChatClient` 时，**只需要改装配处的一个方法**：

```java
@Bean
public CodeModel codeModel() {
    // 课程形态：桩模型，可离线运行
    return StubCodeModel.reliable();

    // 生产形态：换成真实 LLM，其余 19 个包一行都不用动
    // return new SpringAiCodeModel(chatClient);
}
```

同样的模式出现在：`CommandExecutor`（本地执行器 / 进程沙箱）、`FixGenerator`（规则修 / 模型修）、`CodeModel` 之外的所有外部依赖。**这就是端口—适配器架构的全部价值：把「变化」关进接口后面**。

### 3.2 单一装配点

全模块只有一个 Spring 配置类 `api/CodeAgentConfiguration`：

```java
@Configuration
public class CodeAgentConfiguration {

    @Bean
    public VirtualFileSystem demoRepository() {
        return VirtualFileSystem.temp("code-agent-service");
    }

    @Bean
    public CodeModel codeModel() {
        return StubCodeModel.reliable();
    }

    @Bean
    public CodeAgentController codeAgentController() {
        return new CodeAgentController();
    }
}
```

业务模块里**没有一个 `@Service` / `@Component`**。收益有三：

1. **可测试**：`PatchApplier`、`CompileVerifier`、`SelfRepairLoop` 全部能 `new` 出来单测，不需要 Spring 容器；
2. **可替换**：换沙箱不影响补丁，换 LLM 不影响验证；
3. **依赖可见**：想知道系统由什么组成，只看这一个文件就够了，不用满仓库找注解。

### 3.3 错误码：按模块分段

`CodeErrorCode` 用 `CA-Xxxx` 分段，看到码就知道该找哪个模块：

| 段 | 模块 | 典型码 |
| --- | --- | --- |
| `CA-0xxx` | 通用 | `CA-0004 OPERATION_NOT_ALLOWED`（权限不足）、`CA-0005 TENANT_REQUIRED` |
| `CA-1xxx` | `repo` / VFS | `CA-1004 PATH_TRAVERSAL`（路径穿越）、`CA-1005 PATH_OUT_OF_ROOT` |
| `CA-2xxx` | `tools` | `CA-2004 TOOL_TIMEOUT`、`CA-2005 TOOL_DENIED` |
| `CA-3xxx` | `analysis` / `context` | `CA-3003 CONTEXT_OVERFLOW` |
| `CA-4xxx` | `patch` | `CA-4002 PATCH_NOT_APPLIED`、`CA-4004 PATCH_AMBIGUOUS` |
| `CA-5xxx` | `generator` | `CA-5002 GENERATION_OUTPUT_INVALID` |
| `CA-6xxx` | `sandbox` | `CA-6001 SANDBOX_TIMEOUT`、`CA-6004 SANDBOX_COMMAND_DENIED` |
| `CA-7xxx` | `verify` / `iterate` | `CA-7001 COMPILE_FAILED`、`CA-7005 REPAIR_NO_PROGRESS` |
| `CA-8xxx` | `safety` / `review` | `CA-8002 SECRET_LEAKED`、`CA-8003 REVIEW_REJECTED` |
| `CA-9xxx` | `eval` / `metering` | `CA-9002 BUDGET_EXCEEDED`、`CA-9003 QUOTA_EXCEEDED` |

这么做的实际收益：线上收到 `CA-7001` 告警，你立刻知道去查编译验证；收到 `CA-4004`，立刻知道是补丁目标不唯一。**错误码本身就是一张架构地图**。

在 Chapter 03 的 Demo 里，路径穿越被拦截时抛出的就是 `CA-1004`（真实输出）：

```
  ✓ 已拦截 [CA-1004] ../../../../etc/passwd
              → 路径越界：../../../../etc/passwd → C:\Users\13153\AppData\etc\passwd
                不在仓库根 C:\Users\13153\AppData\Local\Temp\day17-vfs\tenant-acme__order-service__task-001 之内
```

---

## 四、真实项目中的应用

在一个真实的 DevOps 平台里，这套分层会这样落地：

| 层 | 生产实现 | 课程实现 |
| --- | --- | --- |
| 模型 | Spring AI `ChatClient`（OpenAI / 通义 / 混元） | `StubCodeModel` |
| 仓库访问 | JGit 拉真实分支到临时目录 | `SampleRepository` 内存夹具 |
| 沙箱 | Docker / gVisor 容器，禁网 | `ProcessSandbox`（进程级） |
| 编译 | `javax.tools` + 增量编译 + 缓存 | `CompileVerifier` 全量编译 |
| 测试 | JUnit 5 + Surefire，Maven/Gradle 调用 | assert + main，沙箱内 `java -ea` |
| 静态分析 | JavaParser / javac Trees API | 正则级 `LightweightJavaParser` |
| 评审 | 规则 + LLM 混合 | `RuleBasedReviewer` 纯规则 |
| 持久化 | 任务表 / Trace 表 / 评测样本表（见 DATABASE.md） | 全内存 |

**演进路径**：课程实现跑通了**全链路的因果**，生产替换只换适配器不动主干。这正是端口—适配器架构的目的——先用便宜的实现验证设计，再逐个换成昂贵的真实实现。

---

## 五、避坑与优化

### 坑 1：让能力模块互相依赖
**后果**：`patch` 依赖 `generator` 后，换模型要连带改补丁逻辑；`verify` 依赖 `orchestrator` 后，测试无法独立运行。
**做法**：能力模块只依赖 `core` 和自己的端口，组装交给 `orchestrator`。

### 坑 2：安全校验散落各处
**后果**：三处校验漏一处就是全线失守，且漏洞极难被发现。
**做法**：所有写操作收敛到 `repo.VirtualFileSystem` 一个入口（Ch03）。

### 坑 3：业务模块里塞 Spring 注解
**后果**：单测要起容器、换实现要到处改、依赖关系看不清。
**做法**：业务模块零注解，只在 `api/CodeAgentConfiguration` 一处装配。

### 坑 4：错误码随意编号
**后果**：线上告警无法定位模块。
**做法**：按模块分段（本课程的 `CA-Xxxx`），并把错误码写进 API 文档。

### 优化建议
- **用包图做评审**：每次新增模块时，先确认它没有引入反向依赖。可以在 CI 里加一条架构约束检查。
- **适配器要成对准备**：每个端口至少准备「课程实现」和「生产实现」两个适配器，前者保证离线可跑，后者保证能上线。

---

## 本章小结

- 19 个模块按「能写哪 / 看什么 / 怎么改 / 对不对 / 能不能过 / 怎么交 / 好不好」七类职责划分。
- 依赖严格单向：`api → orchestrator → … → core`，能力模块之间互不依赖。
- 端口—适配器带来两个收益：**换实现不改调用方**、**单测不需要容器**。
- 业务模块零 Spring 注解，只在 `api/CodeAgentConfiguration` 装配。
- 错误码 `CA-Xxxx` 按模块分段，本身就是一张架构地图。

---

> **下一章预告**：Chapter 03 进入**虚拟文件系统（VFS）**——所有写操作的唯一入口。我们会用四种真实的路径穿越攻击验证它的防护，并顺便揭示一个经典陷阱：为什么用字符串 `startsWith` 做根目录校验是错的。
>
> 👉 输入「继续」进入 Chapter 03。

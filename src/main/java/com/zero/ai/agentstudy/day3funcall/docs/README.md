# Day3：Function Calling —— Agent 真正开始诞生

> 《30 天打造商业级 AI Agent 平台（Java 版）》· Day3
>
> 导师：拥有 20 年经验的 AI Agent 首席架构师 · 面向从 Java/Spring 转型 AI Agent 工程师的你
>
> 一句话定位：**Day2 解决「怎么把大模型接进企业系统」，Day3 解决「怎么让大模型长出手脚、真正动起来」——这是 Agent 从「会说」到「会做」的分水岭。**

---

## 一、今日学习目标

Day2 我们让 Java 应用能调用 LLM 并做多轮对话。但那个 LLM 只会「说」，不会「做」——它不能查实时天气、不能算精确数字、不能发邮件。
Day3 的目标是给 LLM 装上 **Tool（工具）**，让它从「聊天机器人」进化成「能调用外部能力的 Agent」，具体分三个维度：

### 1. 理论能力（脑子里要有地图）

学完你要能清晰讲出：

- **为什么 Agent 需要 Tool**：LLM 的三大天生缺陷（无实时数据、不会精确计算、不能操作外部世界）。
- **Function Calling 是什么**：不是 LLM 自己执行函数，而是「LLM 决策 + 程序执行」的三阶段协议。
- **Agent Loop（智能体循环）**：发 tools 菜单 → LLM 返回 tool_calls → 程序执行回传 → LLM 综合作答。
- **Tool 设计原则**：返回结构化 JSON、单一职责（SRP）、工具六要素规范。
- **多工具语义路由**：LLM 靠 `description` 匹配该调哪个工具。
- **多工具协同**：前一步输出喂给后一步 = Workflow 雏形。
- **Agent 编排 vs 传统 Workflow**：动态决定顺序 vs 硬编码固定路径。

### 2. 工程能力（手上要能落地）

完成一个**能自动选工具的完整 Agent（Agent Assistant V1）**，支持：

| 能力 | 说明 |
| --- | --- |
| 单工具调用 | `/day3/weather`，查询实时天气 |
| 多工具自动选择 | `/day3/agent`，LLM 根据语义选对工具 |
| 多工具串行协同 | `/day3/workflow`，查天气 → 发邮件 |
| 工具目录分组 | `/day3/group`，按场景挂载工具子集 |
| 系统提示约束 | 设定人设、行为边界 |
| 异常兜底 | 工具失败转 error JSON，不炸穿 Agent |

### 3. 架构能力（心里要有分层）

理解企业级 Agent 的工具治理调用链：

```
用户请求(带身份)
 ↓
权限过滤层（按角色筛选可用工具）
 ↓
工具目录 Registry（按场景分组取用）
 ↓
LLM + ChatClient（只见到「该场景+有权限」的工具）
 ↓
日志/审计切面（记录每次调用）
 ↓
工具执行 + 异常兜底（失败转 error JSON）
```

**核心思想：会写工具是入门，能治理上百个工具（分组/权限/日志/异常）才是架构师。**

---

## 二、Function Calling 体系全景

### 2.1 为什么 Tool 是 Agent 的分水岭

一句话：**没有 Tool 的 LLM 只是「更聪明的搜索框」，有了 Tool 才成为「能做事的 Agent」。**

LLM 有三大天生缺陷：

- **无实时数据**：训练截止后的信息一概不知（今天天气、最新股价）。
- **不会精确计算**：本质是概率预测下一个词，算大数常出错。
- **不能操作外部世界**：不能发邮件、下订单、调 API。

Tool 正是补齐这三块短板的「手脚」。

### 2.2 Agent = LLM + Tools + 编排逻辑

```
        Agent
   ┌──────────────┐
   │  LLM  (大脑)   │  负责决策：该不该调工具、调哪个、传什么参
   │  Tools(手脚)   │  负责执行：真正查天气、算数、发邮件
   │  编排 (神经)   │  负责循环：把结果回传给 LLM，直到得出答案
   └──────────────┘
```

### 2.3 核心概念速查表

| 概念 | 是什么 | Java 工程师类比 |
| --- | --- | --- |
| Tool / Function | LLM 可调用的外部能力 | 一个带注解的方法 |
| tool_calls | LLM 决定要调的工具及参数 | 反射要调的方法名 + 入参 |
| Function Calling | LLM 决策 + 程序执行的协议 | 委托回调 |
| Agent Loop | 多轮「调用-回传」循环 | while 循环直到终止条件 |
| @Tool | Spring AI 标注工具方法 | @RequestMapping 之于接口 |
| description | 工具语义说明 | 决定 LLM 选不选它 |
| finish_reason | 停止原因 | stop=完成 / tool_calls=还要调工具 |

---

## 三、Function Calling 请求流程详解

### 3.1 三阶段协议

```
① 第一次请求：Java 把「用户问题 + tools 菜单」发给 LLM
      ↓
② LLM 判断需要工具 → 返回 tool_calls（工具名 + 参数），finish_reason=tool_calls
      ↓
③ 程序执行工具 → 把结果作为 tool 消息回传给 LLM
      ↓
④ LLM 综合工具结果 → 生成自然语言回答，finish_reason=stop
```

关键认知：**LLM 从不自己执行函数，它只「决定调什么」，真正执行的是你的程序。**

### 3.2 Spring AI 如何自动化这个循环

```
chatClient.prompt()
    .system(人设)
    .user(用户问题)
    .tools(weatherTool, timeTool)  // 挂载工具
    .call().content();             // .call() 内部自动跑完整 Agent Loop
```

你只需挂载工具，**多轮「调用-回传」循环由 Spring AI 自动完成**，无需手写循环。

---

## 四、企业级 Agent 工具治理架构

### 4.1 四大支柱

```
① 工具目录（Registry）：按场景/业务域分组，按需挂载，避免 prompt 爆炸
② 权限管理：执行前按角色硬校验，绝不信任 LLM 自觉
③ 可观测：traceId 全链路 + 结构化日志 + 指标监控
④ 异常兜底：工具失败转 error JSON，永不向上抛异常炸穿 Agent
```

### 4.2 为什么要有工具目录（ToolRegistry）

**永远不要把上百个工具全塞给 LLM。**

- **省 token**：每个场景只暴露 5~10 个相关工具。
- **提准确率**：工具越少语义越不重叠，LLM 选得越准。
- **可治理**：分组 + 权限 + 灰度，都集中在这一层。

---

## 五、实战项目介绍：Agent Assistant V1

技术栈：**Java 17 + Spring Boot 3.4.5 + Spring AI 1.0 GA**。

对外提供的接口：

| 接口 | 方法 | 作用 |
| --- | --- | --- |
| `/day3/weather` | GET | 单工具调用 |
| `/day3/agent` | GET | 多工具自动选择 |
| `/day3/workflow` | GET | Weather + Email 串行协同 |
| `/day3/group` | GET | 按工具目录分组挂载 |
| `/day3/tools` | GET | 查看工具目录 |
| `/day3/assistant` | GET | Agent Assistant V1（收官） |

代码资产（本包，全部编译通过）：

- 工具：`WeatherTool` / `TimeTool` / `CalculatorTool` / `EmailTool`
- 服务：`Day3AgentService`（单/多/系统提示对话）
- 治理：`ToolRegistry`（工具目录分组）
- 作品：`AgentAssistantV1`（收官 Agent）
- 入口：`Day3Controller`

---

## 六、面试重点（Day3 高频考点）

1. **为什么 Agent 需要 Tool？** —— LLM 无实时数据、不会精确计算、不能操作外部世界。
2. **Function Calling 是 LLM 自己执行函数吗？** —— 不是，LLM 只决策，程序执行。
3. **描述一次 Function Calling 的完整流程。** —— 三阶段协议 + Agent Loop。
4. **LLM 靠什么决定调哪个工具？** —— 工具的 `description` 语义匹配。
5. **多工具串行协同靠什么实现？** —— LLM 多轮 Agent Loop 动态编排。
6. **Agent 编排和传统 Workflow 引擎的区别？** —— 动态决定顺序 vs 硬编码固定路径。
7. **Tool 设计要遵循哪些原则？** —— 返回 JSON、单一职责、六要素规范。
8. **几十上百个工具怎么管理？** —— 工具目录按场景分组，按需挂载。
9. **工具权限怎么控制？** —— 执行前代码硬校验，不依赖 LLM。
10. **工具异常怎么处理才不影响 Agent？** —— 捕获转 error JSON，永不上抛。

---

## 七、今日章节安排（严格串行，逐章暂停）

| 章节 | 主题 | 完成后 |
| --- | --- | --- |
| 第一章 | 为什么 Agent 需要 Tool | 暂停，回答「LLM 的三大缺陷是什么？」 |
| 第二章 | Function Calling 工作原理 | 暂停，解释「一次工具调用经历了什么？」 |
| 第三章 | Tool 设计原则 | 暂停，用六要素设计一个工具 |
| 第四章 | Spring AI Tool Calling | 完成 WeatherTool 实战 |
| 第五章 | Java 实现三个 Tool | 完成 Calculator/Time/Weather |
| 第六章 | 多个 Tool 协同工作 | 验证 Weather+Email 串行协同 |
| 第七章 | 企业最佳实践 | 理解工具治理四大支柱 |
| 第八章 | 完成 Agent Assistant V1 | 整合成完整 Agent 作品 |

> **学习原则：一次只讲一章，讲完暂停，等你确认并回答章末问题后，再进入下一章。**

---

## 八、章节文档索引

- [第一章：为什么 Agent 需要 Tool](chapters/chapter-01.md)
- [第二章：Function Calling 工作原理](chapters/chapter-02.md)
- [第三章：Tool 设计原则](chapters/chapter-03.md)
- [第四章：Spring AI Tool Calling](chapters/chapter-04.md)
- [第五章：Java 实现三个 Tool](chapters/chapter-05.md)
- [第六章：多个 Tool 协同工作](chapters/chapter-06.md)
- [第七章：企业最佳实践](chapters/chapter-07.md)
- [第八章：完成 Agent Assistant V1](chapters/chapter-08.md)

---

## 九、目录约定

- 本日所有代码与文档独立于前几日，禁止修改既有学习代码。
- Java 包名：`com.zero.ai.agentstudy.day3funcall`
- 文档目录：`day3funcall/docs/`（README、chapters/）

现在，请从 **第一章：为什么 Agent 需要 Tool** 开始学习。
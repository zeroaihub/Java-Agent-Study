# Day17 · ARCHITECTURE（系统架构）

> 本文说明 Code Agent 的**分层、依赖方向、三条主流程（任务流 / 安全流 / 验证流）与关键设计决策**。

---

## 一、分层与依赖方向

```
┌──────────────────────────────────────────────────────────────┐
│  api / orchestrator     应用层：REST 接口 + 主循环编排           │
└──────────────────────────────────────────────────────────────┘
          │                │                 │
          ▼                ▼                 ▼
┌──────────────┐  ┌──────────────┐  ┌────────────────────────┐
│ gitops       │  │ eval         │  │ observability/metering │
│ 变更集 + PR  │  │ 评测 + Pass@k│  │ Trace + 预算护栏        │
└──────────────┘  └──────────────┘  └────────────────────────┘
          │                │                 │
          └────────────────┴─────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────────┐
│ iterate（自修复） → verify（编译/测试） → sandbox（执行）        │
│ safety（安全审查）  patch（补丁）                                │
└──────────────────────────────────────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────────┐
│ context（预算/选择） → analysis（符号/依赖） → repo（VFS/快照）   │
│ testgen（测试生成）    generator（模型端口 + 提示词）              │
└──────────────────────────────────────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────────┐
│ core：强类型 ID / CodeTaskContext / CodeResult / CodeException │
└──────────────────────────────────────────────────────────────┘
```

**规则**：箭头单向，上层可依赖下层，下层绝不知道上层存在。业务模块零 Spring 注解。

**为什么值得这么严格**：Code Agent 是最容易被「换个模型 / 换个沙箱 / 换个仓库后端」拖垮的系统。把变化点收敛成端口 + 适配器之后，这些替换都只改 `api/CodeAgentConfiguration` 一个文件。

---

## 二、任务主流程（Happy Path）

```
1  上下文选择      ContextSelector
     └─ 符号命中(6.0) + 关键词(2.0) + 依赖联动(3.0) + 路径提示(1.5)
     └─ 按预算装填：FULL → SIGNATURE → 丢弃
2  提示词装配      PromptAssembler
     └─ 系统提示词（角色 + 铁律 + 输出格式契约）
     └─ 用户提示词（需求 + 影响面 + 选中代码）
3  模型调用        CodeModel（预算护栏先扣额度）
4  补丁解析        PatchParser（SEARCH/REPLACE 或 Unified Diff）
5  安全审查        PatchSafetyChecker + SecretScanner
     └─ REJECT → 立即返回，不浪费后续任何验证
6  补丁应用        PatchApplier → VirtualFileSystem
7  编译验证        CompileVerifier（javax.tools.JavaCompiler）
8  自修复循环      SelfRepairLoop（最多 N 轮，三道刹车）
9  测试验证        TestVerifier（沙箱内执行）
10 规则评审        RuleBasedReviewer（只评审 diff 的 + 行）
11 变更集 + PR     ChangeBundle → 风险分级 → PullRequestDraft
12 Trace           TaskTrace（8~10 步，带耗时与 token）
```

---

## 三、安全流：四层闸门

```
第 1 层  租户 / 角色
         CodeTaskContext.canWrite() / canExecute()
         请求体里没有 tenantId / userId —— 只能由网关注入
                    │
第 2 层  路径
         VirtualFileSystem.resolve()
         统一分隔符 → 拒绝绝对路径 → normalize 消解 .. → Path.startsWith 按元素比较根
                    │
第 3 层  命令
         SandboxPolicy：可执行文件白名单 + 参数黑名单 + 工作目录 + 超时 + 环境隔离
                    │
第 4 层  落盘前审查
         PatchSafetyChecker：受保护路径 / 敏感文件 / 删除行数 / 高危 API
         SecretScanner：正则扫描疑似密钥，命中即拦截，日志只记脱敏结果
```

**关键时序**：第 4 层必须在**打补丁之前**。放在之后，等于先让它把密钥写进仓库、再报警，而此时文件可能已经被提交。

---

## 四、验证流：编译 → 测试 → 评审

```
                ┌──────────────────┐
                │  Patch Applier   │
                └────────┬─────────┘
                         ▼
                 CompileVerifier
                 （javax.tools，结构化诊断）
                         │
              ┌────失败──┴────成功────┐
              ▼                       ▼
       SelfRepairLoop            TestVerifier
    （反馈 → 重生成 → 重编译）      （沙箱 java -ea 执行）
       轮次上限 / 无进展 / 指纹          │
              │                    ┌────┴────┐
              └───────────────────►│  通过   │
                                   └────┬────┘
                                        ▼
                                RuleBasedReviewer
                                        │
                                        ▼
                              ChangeBundle → PR
```

**三道验证各自拦什么**：

| 环节 | 能拦住 | 拦不住 |
| --- | --- | --- |
| 编译 | 语法错误、类型错误、符号不存在 | 逻辑错误 |
| 测试 | 行为回归、边界错误 | 未覆盖的场景 |
| 评审 | 反模式、安全红线、风格 | 业务语义是否合理 |

实测证据（Ch12）：把 `Money.zero()` 改成返回 1，**编译依然通过**，只有测试抓住了这个错误。

---

## 五、关键设计决策（ADR 摘要）

### ADR-1：用 SEARCH/REPLACE 而不是整文件重写

**决策**：强制最小改动面。
**理由**：整文件重写会让模型「顺手」改动无关代码，且 diff 里看不出它改了哪。实测输出 token 从 191 降到 88（省 53.9%）。
**代价**：补丁可能打不上，需要自修复循环兜底。

### ADR-2：打不上的补丁必须明确失败

**决策**：禁止「模糊近似替换」。
**理由**：Demo 阶段模糊匹配成功率很好看，但它把确定性变成概率——改到错误位置时代码看起来还正常，几个月后才以完全不相干的形式爆出来。
**代价**：补丁应用率下降，需要靠高质量的上下文与自修复来补。

### ADR-3：验证用 `javax.tools.JavaCompiler` 而不是 `Runtime.exec("javac")`

**决策**：优先用编译器 API。
**理由**：诊断结构化（file/line/column/code）、无进程启动开销（片段编译 34ms vs 整仓 754ms）、不必处理 Windows 路径与编码。
**代价**：耦合到 JDK（JRE 上不可用），因此必须显式 `CompileVerifier.available()` 自检。

### ADR-4：能规则化的检查不交给 LLM

**决策**：评审、安全扫描、路径校验一律用确定性规则。
**理由**：模型评审会漏、会奉承、会随 temperature 抖动。
**代价**：规则需要维护，覆盖不到的部分仍需模型。

### ADR-5：工具失败是返回值不是异常

**决策**：`ToolResult` 同时表达成功与失败。
**理由**：「文件不存在」是常态分支，用异常表达会污染控制流，也让自修复循环无法把失败信息喂回模型。

### ADR-6：预算必须在动作之前检查

**决策**：`BudgetGuard` 的 `acquire*` 全部 synchronized。
**理由**：事后统计只能告诉你超支了多少钱；事前检查才能阻止超支。check-then-act 不做原子化，并发任务会一起通过检查。

---

## 六、并发与线程模型

- 上下文选择、符号解析在主线程串行（耗时量级 10~100ms，不值得并行）；
- 编译验证走 JDK 编译器，内部自带并发；
- 沙箱执行每个进程独立，输出必须用**独立线程**读取（否则缓冲区写满后子进程阻塞，`waitFor` 永远超时）；
- `BudgetGuard` 所有方法 `synchronized`，保证检查与扣减原子；
- `CodeTaskContext` 是 `record`（不可变），可安全跨线程传递。

---

## 七、扩展性：换实现只需改一处

| 想换 | 改哪里 |
| --- | --- |
| 换成真实 LLM | `CodeAgentConfiguration.codeModel()` |
| 换成真实 git 仓库 | `CodeAgentConfiguration.demoRepository()` |
| 换成容器沙箱 | 新增一个 `CommandExecutor` 实现 |
| 换成 JavaParser | 替换 `LightweightJavaParser.parse()` 内部实现 |
| 换成 JUnit 5 | 替换 `TestGenerator.render()` 与 `TestVerifier` |

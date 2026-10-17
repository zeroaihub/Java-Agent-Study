# Day17 · SECURITY（安全设计）

> Code Agent 是**所有 Agent 里风险最高的一个**——它会主动修改代码、主动执行程序。本文列出四层护栏与已知残余风险。

---

## 一、威胁模型

| 威胁 | 后果 | 防线 |
| --- | --- | --- |
| 路径穿越（`../../etc/passwd`） | 读写仓库外任意文件 | VFS 根校验 |
| 危险命令（`rm -rf` / `shutdown`） | 数据丢失、主机不可用 | 沙箱白名单 + 参数黑名单 |
| 死循环 / 内存炸弹 | CPU 占满、OOM | 超时 + 输出上限 |
| 读取环境变量里的密钥 | 凭证泄露 | 环境变量隔离 |
| 把密钥写进代码 | 进仓库即事故 | SecretScanner + 落盘前拦截 |
| 改 CI 配置 / 构建文件 | 供应链攻击 | 路径黑名单 / 敏感文件告警 |
| 无权限用户提交编码任务 | 越权 | 角色校验（默认拒绝） |
| 客户端自称租户 | 跨租户 | 租户只能由网关注入 |
| 预算失控 | 巨额账单 | BudgetGuard |
| 日志打印密钥明文 | 二次泄露 | 强制脱敏 |

---

## 二、四层护栏

### 第 1 层：租户与角色

- `CodeTaskContext` 是 `record`（不可变），租户不可中途被改；
- `canWrite()` / `canExecute()` **默认拒绝**：没有 `CODE_WRITER` / `CODE_EXECUTOR` 或 `ADMIN` 就不放行；
- REST 请求体**不含** `tenantId` / `userId`，只能由网关通过 Header 注入。

### 第 2 层：路径

`VirtualFileSystem.resolve()` 四步：

1. 统一分隔符（Windows `\` → `/`）；
2. 拒绝绝对路径（本身就是越权信号）；
3. `normalize()` 消解 `.` 与 `..`；
4. **`Path.startsWith(root)` 按路径元素比较**。

> **必须避开的坑**：用 `candidate.toString().startsWith(root.toString())` 是错的——根 `/tmp/work` 会放行 `/tmp/workplace-evil/x`。实测（Ch03）：字符串判定 `true`（放行），`Path.startsWith` `false`（拦截）。

### 第 3 层：命令与沙箱

`SandboxPolicy` 五个字段：

| 字段 | 默认 | 作用 |
| --- | --- | --- |
| `allowedExecutables` | `java, javac` | 默认拒绝，只放行明确需要的 |
| `forbiddenArgs` | `rm -rf`、`sudo`、`mkfs` 等 | 多一道保险 |
| `timeoutMs` | 30000 | 超时即 `destroyForcibly` |
| `maxOutputBytes` | 256KB | 防输出打爆内存 |
| `inheritEnv` | **false** | 防密钥泄露 |

**两个实现要点**：

1. **无 shell**：`ProcessBuilder` 直接 exec，不经 `cmd /c` 或 `sh -c`，因此 `;`、`|`、反引号全部失效；
2. **输出必须异步读**：子进程 stdout 缓冲区写满后会阻塞等待读取，若主线程先 `waitFor` 再读，会表现为「很快的程序却永远超时」。

### 第 4 层：落盘前审查

`PatchSafetyChecker` 四查：受保护路径（REJECT）→ 敏感文件（WARN）→ 删除行数（REJECT）→ 高危 API（WARN）。

`SecretScanner` 七条规则：OpenAI Key、AWS Key、GitHub Token、Slack Token、私钥、通用密码赋值、JDBC 密码。

> **脱敏是硬要求**：命中后日志只记 `redact()` 结果（前 4 字符 + 星号）。把原文明文打进日志，等于把刚拦下的密钥又泄露一遍——而日志通常比代码更容易被访问。

---

## 三、残余风险（诚实交代）

| 风险 | 现状 | 生产应如何做 |
| --- | --- | --- |
| 进程级沙箱不是容器级 | 可绕过：本地提权、文件系统其余部分仍可读 | gVisor / Kata / Docker + 只读挂载 + 禁用网络 |
| 无网络隔离 | 生成的程序可外联 | 沙箱网络 namespace 隔离或 iptables 白名单 |
| 正则密钥检测有误报漏报 | 高度定制的格式查不出 | 配合 gitleaks / truffleHog 做 CI 门禁 |
| 无文件系统配额 | 可写满磁盘 | 目录配额 + tmpfs 限制大小 |
| 无 CPU / 内存硬限制 | 只能靠超时兜底 | cgroups v2：`memory.max` + `cpu.max` |
| 模型可能被提示词注入 | 仓库里的代码可含诱导指令 | 输入隔离 + 提示词注入检测（Day16 `PromptInjectionGuard`） |

---

## 四、安全测试清单（必须有的负向用例）

- [ ] 路径穿越 5 种写法全部被拦截
- [ ] 绝对路径 / Windows 盘符被拦截
- [ ] 字符串前缀「同根名」绕过被拦截
- [ ] 白名单外可执行文件被拒绝
- [ ] 参数命中黑名单即使可执行文件合法也被拒绝
- [ ] 工作目录越界被拒绝
- [ ] 超时能强杀，且 `timedOut` 与 `exitCode` 可区分
- [ ] 无写权限用户调用 `write_file` 被拒绝
- [ ] 无执行权限用户调用 `run_command` 被拒绝
- [ ] 改 `.github/workflows/*` 被 REJECT
- [ ] 含 `sk-proj-...` 的补丁被拦截且日志脱敏
- [ ] 只读模式注册表中不存在 `write_file`（模型根本看不到）

---

## 五、审计要求

每一次任务至少留存：谁（tenant + user + roles）、什么时候、改了哪些文件、用了什么匹配策略、编译与测试结果、Trace 全步骤、预算消耗。

`patch_record.strategy` 与 `review_comments` 尤其重要——它们记录了「这个改动是怎么来的、系统对它有什么保留意见」。

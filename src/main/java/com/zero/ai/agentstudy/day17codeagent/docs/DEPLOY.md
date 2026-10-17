# DEPLOY · Code Agent

本文件把 Ch10（沙箱）、Ch11（编译）、Ch19（可观测/预算）的工程约束沉淀为**可执行部署手册**。

与常规 Web 服务不同，Code Agent 的头号风险不是并发，而是**它会往磁盘写文件、会启动子进程**。因此部署清单的重心是「把 Agent 的能力边界用基础设施再钉一层」，而不是调线程池。

---

## 一、运行时依赖

| 组件 | 版本 | 用途 | 章节 |
| --- | --- | --- | --- |
| JDK（**必须是 JDK，不能是 JRE**） | 21 | `javax.tools.JavaCompiler` 只存在于 JDK；JRE 下 `CompileVerifier.available()` 返回 false | Ch11 |
| 可写工作目录 | — | VFS 根 / 沙箱根 / 编译输出目录，三者建议独立挂载并限额 | Ch03 / Ch10 |
| Docker | 24+ | 把「进程级沙箱」升级为「容器级沙箱」 | Ch10 / SECURITY.md |
| Kubernetes | 1.29+ | 编排 | 本节 |
| 模型服务 | — | 生产需接真实 LLM；本课程用 `StubCodeModel` 替身 | Ch09 |

> **最容易踩的坑**：镜像里装了 JRE。此时 `ToolProvider.getSystemJavaCompiler()` 返回 `null`，编译验证直接不可用，而自修复循环会因为没有反馈而彻底失效。Ch11 的 `[0] 运行环境自检` 就是为此准备的。

---

## 二、三种部署形态

| 形态 | 适用场景 | 优点 | 注意 |
| --- | --- | --- | --- |
| **嵌入式库**（当前形态） | 把编码能力嵌进现有 DevOps 平台 | 零端口、零运维 | 与主服务共享资源，需靠预算护栏隔离 |
| **独立微服务** | 多业务方复用 | 独立扩缩容、故障隔离 | 必须做任务级超时与排队 |
| **异步 Worker** | 任务耗时长（编译+测试动辄数秒） | 不占 HTTP 连接，可重试 | 需要任务状态存储（见 DATABASE.md 的 `code_task`） |

> 一个编码任务常常要跑多轮「编译 → 测试 → 修复」，耗时远高于普通请求。**HTTP 同步等待很容易超时**，任务量上来后建议直接走 Worker 形态。

---

## 三、目录与权限（关键）

```
/work
  ├── vfs/         # VirtualFileSystem 根：Agent 能写的所有文件都在这里
  ├── sandbox/     # ProcessSandbox 根：子进程的工作目录必须在此之内
  └── classes/     # 编译输出目录
```

| 要求 | 原因 |
| --- | --- |
| 三个目录**独立且专属** | 沙箱根与 VFS 根混用会让「越权写文件」与「合法工作目录」难以区分 |
| 运行用户**非 root** | 进程被攻破后的横向影响最小 |
| 定期清理（按 `tenant__repo__task` 目录维度，见 `CodeTaskContext.sandboxDirectoryName()`） | 每个任务都会留下源码与 class，长期运行会撑爆磁盘 |
| 磁盘配额（容器用 `--storage-opt size=`，K8s 用 `emptyDir.sizeLimit`） | 防止 Agent 生成海量文件把节点打挂 |

---

## 四、配置项（全部外置，占位符见 `.env.example`）

| 变量 | 说明 | 敏感 |
| --- | --- | --- |
| `CODE_AGENT_VFS_ROOT` / `SANDBOX_ROOT` / `CLASSES_ROOT` | 三个工作目录 | 否 |
| `CODE_AGENT_ALLOWED_EXECUTABLES` | 沙箱可执行文件白名单，默认 `java,javac` | 否 |
| `CODE_AGENT_TIMEOUT_MS` | 单条命令超时（默认 30000） | 否 |
| `CODE_AGENT_INHERIT_ENV` | 是否继承宿主环境变量，**生产必须 false** | 否 |
| `CODE_AGENT_MAX_REPAIR_ROUNDS` | 自修复轮次上限（默认 3） | 否 |
| `CODE_AGENT_MAX_TOKENS` / `MAX_MODEL_CALLS` | 预算护栏 | 否 |
| `CODE_AGENT_MODEL_API_KEY` | 模型密钥 | **高敏，只走 Secret** |

> `inheritEnv` 一旦为 true，Agent 生成的代码可以用 `System.getenv("OPENAI_API_KEY")` 把密钥打印出来——Ch10 实测宿主环境变量有 237 个，里面几乎必然有敏感项。

---

## 五、容器化加固

进程级沙箱（`ProcessSandbox`）在本课程里已经做了白名单、超时、环境隔离，但它**不隔离文件系统与网络**。容器是第二层：

```dockerfile
# 关键要点（示意）
FROM eclipse-temurin:21-jdk          # 必须是 JDK
RUN useradd -m -u 10001 cagent
USER 10001                            # 非 root
VOLUME ["/work"]
# 启动参数在编排层附加（见下）
```

运行时的加固参数：

```bash
docker run --rm \
  --read-only                         \  # 根文件系统只读，只有 /work 可写
  --tmpfs /tmp:size=256m              \
  --pids-limit 256                    \  # 防止 fork 炸弹
  --memory 2g --cpus 2                \
  --network=none                      \  # 绝大多数编码任务不需要出网
  --security-opt no-new-privileges    \
  -v code-agent-work:/work            \
  code-agent:1.0.0
```

> `--network=none` 是最容易被忽略、收益却最高的一条：它同时掐断了「把源码传出去」和「下载并执行恶意依赖」两条路。若确实需要拉依赖，应改为白名单代理而非全放开。

---

## 六、Kubernetes 要点

```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 10001
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities: { drop: ["ALL"] }
resources:
  requests: { cpu: "1", memory: "1Gi" }
  limits:   { cpu: "2", memory: "2Gi" }
volumes:
  - name: work
    emptyDir: { sizeLimit: 2Gi }     # 工作目录限额
```

- **NetworkPolicy**：默认 `deny-all` egress，只对模型网关与镜像仓库放行；
- **探针**：liveness 查自身（进程存活），readiness 查 `CompileVerifier.available()`——JDK 缺失时应直接摘流量而不是反复重启；
- **优雅停机**：收到 `SIGTERM` 先停止接新任务，等待在途任务落盘（预算护栏的墙钟上限会兜底），再退出。

---

## 七、发布前检查清单

- [ ] 镜像内是 **JDK** 而非 JRE，Ch11 自检 `JavaCompiler 可用 = true`；
- [ ] VFS 根 / 沙箱根 / classes 目录三者分离，且非 root 可写；
- [ ] `inheritEnv=false`、可执行文件白名单只有 `java,javac`；
- [ ] 工作目录有磁盘配额与定期清理策略；
- [ ] 容器 `--network=none`（或 egress 白名单）、`--pids-limit`、非 root、只读根；
- [ ] 预算护栏阈值与租户套餐一致（Ch19）；
- [ ] 密钥走 Secret，镜像与 ConfigMap 内无明文（Ch14 的 `SecretScanner` 会拦代码里的硬编码密钥）；
- [ ] Ch18 评估集在 CI 通过，Pass@1 / 编译通过率不回退；
- [ ] 回滚预案演练过。

---

## 八、故障速查

| 现象 | 排查方向 |
| --- | --- |
| 所有任务都「编译未通过」且无诊断 | 镜像是 JRE；查 `CompileVerifier.available()` |
| 任务全部超时 | 子进程输出未异步读取导致阻塞（Ch10）；或 CPU 限额过低 |
| 工作目录迅速占满 | 未做任务目录清理；`emptyDir.sizeLimit` 未设置 |
| 偶尔出现越权写文件告警 | VFS 根与沙箱根是否混用；`Path.startsWith` 校验是否被绕过 |
| token 消耗突然飙升 | 自修复失控，检查 `stopOnNoProgress` 是否开启（Ch13）与预算护栏阈值 |
| 编译产物错乱 | 多个任务复用了同一个 classes 输出目录 |

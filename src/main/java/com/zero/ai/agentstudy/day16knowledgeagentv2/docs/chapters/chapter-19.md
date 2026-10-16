# Chapter 19 · 部署与运维

> **本章目标**:把前 18 章构建的 Knowledge Agent 从"能在 IDE 里跑"变成"能稳定跑在生产 K8s 上"。覆盖容器化、配置管理、健康探针、优雅发布、弹性伸缩与依赖编排,并把 Ch14(可观测)/ Ch17(性能)/ Ch18(安全)的能力落到运维层面。

---

## 1. 为什么学(Why)

### 1.1 "在我机器上能跑"不是交付

一个 Agent 依赖一长串外部组件:PostgreSQL + pgvector、Elasticsearch、Redis、大模型 API、Reranker 服务……本地跑通 ≠ 能上线。生产环境要回答的是:

- 怎么保证**每次部署环境一致**?(不同机器 JDK/依赖版本漂移是经典事故)
- 怎么**不停机发布**新版本?
- 某个 Pod 卡死了,平台怎么**自动发现并替换**?
- 流量突增,怎么**自动扩容**?
- 配置(数据库密码、模型密钥)怎么**安全注入**、按环境区分?

这些是"能不能持续、稳定地服务客户"的问题,和写业务代码同等重要。

### 1.2 云原生:把运维知识固化成声明式配置

**坑**:靠人肉 SSH 上服务器改配置、手动重启——不可复现、易出错、无法审计。

**对**:**云原生(Cloud Native)**——把"应用怎么打包、怎么部署、怎么伸缩、怎么自愈"全部写成**声明式配置**(Dockerfile / K8s YAML),纳入版本控制。运维从"操作"变成"改代码 + review + 应用",可复现、可回滚、可审计。

### 1.3 部署方式决定可用性

发布是最高危操作——多数生产事故发生在发布时刻。粗暴的"停旧启新"会造成停机与请求丢失。本章要讲**优雅发布**(滚动更新 + 就绪探针 + 优雅停机),让用户对发布无感知。

---

## 2. 是什么(What)

### 2.1 从代码到生产的四级火箭

```
源码 → 构建镜像(Docker)→ 推送镜像仓库 → K8s 拉取并编排运行
        └ 分层构建、最小化    └ 打 tag/摘要   └ Deployment/Service/HPA/探针
```

### 2.2 容器化:分层与瘦身

- **多阶段构建(multi-stage)**:第一阶段用完整 JDK + Maven 编译,第二阶段只拷贝产物到精简 JRE 镜像——**构建工具不进生产镜像**,体积小、攻击面小(呼应 Ch18)。
- **分层缓存**:依赖层与代码层分开,代码改动不必重新下载依赖,构建快。
- **非 root 运行**:容器内用非特权用户,最小权限(Ch18 纵深防御延伸到运行时)。

### 2.3 K8s 核心对象(部署一个 Agent 需要哪些)

| 对象 | 职责 |
| --- | --- |
| **Deployment** | 声明期望副本数、镜像、滚动更新策略,负责自愈与发布 |
| **Service** | 给一组 Pod 一个稳定的内部访问入口(负载均衡) |
| **ConfigMap** | 非敏感配置(日志级别、批大小、feature flag) |
| **Secret** | 敏感配置(DB 密码、模型 API Key)——不进镜像、不进 Git |
| **HPA** | 按 CPU/QPS 自动伸缩副本数 |
| **探针** | liveness/readiness/startup,让平台知道 Pod 健康状态 |

### 2.4 三种健康探针(K8s 靠它判断死活)

| 探针 | 回答的问题 | 失败后果 |
| --- | --- | --- |
| **liveness(存活)** | 进程死了吗/卡死了吗? | 重启 Pod |
| **readiness(就绪)** | 能接流量吗?(依赖就绪吗) | 从 Service 摘除,不发流量 |
| **startup(启动)** | 启动完成了吗?(JVM 慢启动) | 给足启动时间,避免误杀 |

区分它们至关重要:依赖(如向量库)抖动时,应让 readiness 失败**摘流量**,而**不是** liveness 失败**重启**——重启解决不了外部依赖问题,反而雪崩。

### 2.5 配置管理:一份镜像,多套环境

同一个镜像跑遍 dev/staging/prod,差异**全部外置**到环境变量/ConfigMap/Secret(12-Factor App 原则)。绝不为每个环境打不同镜像,也绝不把配置硬编码进代码或镜像。

### 2.6 本章交付物

- `Dockerfile`:多阶段构建、精简 JRE、非 root。
- `docker-compose.yml`:本地一键起全套依赖(PG+pgvector / ES / Redis)。
- `application.yml` 片段:虚拟线程(Ch17)、actuator 健康端点、外置配置。
- K8s 清单:`deployment.yaml` / `service.yaml` / `hpa.yaml` / `configmap.yaml` / `secret.yaml`。
- `GracefulShutdown` 说明与配置:优雅停机,发布不丢请求。

---

## 3. 怎么用(How)

> 本章交付物是运维配置文件,放在项目根的 `deploy/` 目录。以下均为可直接使用的模板。

### 3.1 Dockerfile:多阶段构建 + 精简 + 非 root

```dockerfile
# ---------- 阶段一:构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# 先拷 pom 单独下依赖,利用分层缓存(代码变了也不必重下依赖)
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---------- 阶段二:运行 ----------
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
# 创建非 root 用户运行(最小权限)
RUN groupadd -r app && useradd -r -g app app
COPY --from=build /app/target/*.jar app.jar
# 容器感知的 JVM 参数:按 cgroup 限额自动算堆
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseZGC"
USER app
EXPOSE 8080
# 用 exec 形式,保证 JVM 是 1 号进程、能收到 SIGTERM(优雅停机的前提)
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

### 3.2 docker-compose.yml:本地一键起依赖

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg17
    environment:
      POSTGRES_DB: knowledge
      POSTGRES_USER: agent
      POSTGRES_PASSWORD: dev-only-change-me
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U agent -d knowledge"]
      interval: 5s
      timeout: 3s
      retries: 10

  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.15.0
    environment:
      discovery.type: single-node
      xpack.security.enabled: "false"
    ports: ["9200:9200"]

  redis:
    image: redis:8-alpine
    ports: ["6379:6379"]
```

> `healthcheck` 让 compose 知道依赖何时真正就绪——应用启动应等依赖 healthy。

### 3.3 application.yml:外置配置 + actuator + 虚拟线程

```yaml
spring:
  application:
    name: knowledge-agent-v2
  threads:
    virtual:
      enabled: true          # Ch17:开启虚拟线程,thread-per-request 高并发
  datasource:
    url: ${DB_URL}           # 全部外置,一份镜像多套环境
    username: ${DB_USER}
    password: ${DB_PASSWORD} # 来自 K8s Secret

server:
  shutdown: graceful         # 优雅停机:停止接新请求,等在途请求处理完
  port: 8080

# 优雅停机的最长等待时间
spring.lifecycle.timeout-per-shutdown-phase: 30s

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus   # 只暴露必要端点(Ch18 最小暴露面)
  endpoint:
    health:
      probes:
        enabled: true       # 启用 K8s 风格的 liveness/readiness 分组
      show-details: never   # 不对外泄露依赖细节
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
```

> Spring Boot 内置 `/actuator/health/liveness` 与 `/actuator/health/readiness`,K8s 探针直接指过去即可。

### 3.4 K8s Deployment:滚动更新 + 三探针 + 资源限额

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: knowledge-agent
  labels: { app: knowledge-agent }
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1          # 发布时最多多起 1 个
      maxUnavailable: 0    # 发布时不减少可用实例——不停机
  selector:
    matchLabels: { app: knowledge-agent }
  template:
    metadata:
      labels: { app: knowledge-agent }
    spec:
      containers:
        - name: agent
          image: registry.example.com/knowledge-agent:1.0.0  # 用固定 tag/摘要,勿用 latest
          ports: [{ containerPort: 8080 }]
          envFrom:
            - configMapRef: { name: knowledge-agent-config }
            - secretRef: { name: knowledge-agent-secret }
          resources:
            requests: { cpu: "500m", memory: "1Gi" }
            limits: { cpu: "2", memory: "2Gi" }
          # 启动探针:JVM 慢启动,给足时间再开始其它探测
          startupProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            failureThreshold: 30
            periodSeconds: 5
          # 存活探针:进程卡死才重启
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            periodSeconds: 10
          # 就绪探针:依赖没好就摘流量(而非重启)
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            periodSeconds: 5
```

### 3.5 Service / ConfigMap / Secret / HPA

```yaml
apiVersion: v1
kind: Service
metadata:
  name: knowledge-agent
spec:
  selector: { app: knowledge-agent }
  ports: [{ port: 80, targetPort: 8080 }]
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: knowledge-agent-config
data:
  DB_URL: "jdbc:postgresql://postgres:5432/knowledge"
  DB_USER: "agent"
  LOG_LEVEL: "INFO"
---
apiVersion: v1
kind: Secret
metadata:
  name: knowledge-agent-secret
type: Opaque
stringData:
  DB_PASSWORD: "<从密钥管理系统注入,勿提交真实值>"
  LLM_API_KEY: "<从密钥管理系统注入,勿提交真实值>"
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: knowledge-agent
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: knowledge-agent
  minReplicas: 3
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: { type: Utilization, averageUtilization: 70 }
```

> Secret 里**绝不提交真实值**(Ch18 红线),生产从 Vault / 云 KMS 注入。

### 3.6 优雅停机:发布不丢请求

配合前面 `server.shutdown: graceful`,发布流程是:

1. K8s 发 `SIGTERM` → Pod 进入 terminating,**从 Service 摘除**(不再接新流量);
2. 应用停止接受新请求,但**等在途请求处理完**(最多等 `timeout-per-shutdown-phase`);
3. 处理完后进程退出。

关键:Dockerfile 用 `exec` 形式让 JVM 成为 1 号进程,才能收到 `SIGTERM`;否则被 shell 吞掉,优雅停机失效,发布时会丢请求。

---

## 4. 真实项目落地

### 4.1 CI/CD:从提交到生产的自动流水线

- **CI**:提交 → 编译 → 单测 + Ch13 评估用例 + Ch18 红队用例 → 构建镜像 → 推仓库(打 commit SHA tag);
- **CD**:更新 K8s 镜像 tag → 滚动发布 → 观察 Ch14 指标(错误率/P99)→ 异常自动回滚。
- 用 GitOps(Argo CD / Flux):K8s 期望状态全在 Git,发布=改 Git,天然可审计可回滚。

### 4.2 依赖就绪与 readiness 联动

自定义 readiness 检查应真实探测关键依赖(PG/向量库/Redis)。依赖挂了 readiness 转 fail → 摘流量;依赖恢复自动重新接流量。**别把依赖故障做成 liveness 失败**,否则全体重启雪崩。

### 4.3 灰度/金丝雀发布

高风险变更先放 5% 流量到新版本,观察 Ch14 质量指标(Ch13 的 groundedness、错误率)达标再逐步放量。出问题只影响小部分用户,秒级回滚。

### 4.4 可观测性上生产(呼应 Ch14)

- 指标 → Prometheus 抓 `/actuator/prometheus` → Grafana 看板 + 告警;
- 日志 → 结构化 JSON,带 traceId,采集到 Loki/ELK;
- 链路 → OpenTelemetry 上报到 Jaeger/Tempo。
运维排障靠这三支柱,和 Ch18 审计日志各司其职。

### 4.5 有状态依赖不要塞进无状态部署

Agent 应用本身**无状态**(可随意扩缩/重启),状态全在 PG/向量库/Redis。数据库等有状态组件用云托管服务或 StatefulSet + 持久卷单独管理,别和应用混在一起。

---

## 5. 避坑与优化

### 5.1 镜像别用 latest,别把构建工具带进生产

**坑**:`image: xxx:latest` 导致每次拉到的镜像不确定,无法复现、无法回滚;把 Maven/JDK 打进运行镜像,体积大、攻击面大。

**对**:镜像用**不可变 tag(commit SHA)或摘要**;多阶段构建,运行镜像只留 JRE + jar。

### 5.2 探针配错比不配还危险

**坑**:liveness 探针去查数据库——数据库一抖,所有 Pod liveness 失败被同时重启,彻底雪崩。

**对**:liveness 只查**自身进程健康**(轻量、不依赖外部);依赖检查放 readiness。startup 给足 JVM 启动时间,避免慢启动被 liveness 误杀。

### 5.3 不设资源 requests/limits

**坑**:不设 limits,一个 Pod 内存泄露吃光节点,拖垮同节点其它服务;不设 requests,调度器无法合理放置。

**对**:压测得出合理值并设置;JVM 用 `MaxRAMPercentage` 让堆随 cgroup 限额自适应,别写死 `-Xmx`。

### 5.4 忘了优雅停机

**坑**:`SIGTERM` 直接杀进程,在途请求全部失败,发布/缩容时用户报错。

**对**:`server.shutdown: graceful` + Dockerfile `exec` 形式 + K8s `terminationGracePeriodSeconds` 给够时间。

### 5.5 配置漂移与密钥泄露

**坑**:不同环境手改配置导致漂移;把密钥硬编码或提交进 Git。

**对**:配置全走 ConfigMap/Secret,一份镜像多套环境;密钥从 Vault/KMS 注入,Git 里只留占位符(Ch18 红线)。

---

## 本章小结

- 交付不是"能跑",而是"能在生产 K8s 上稳定、可复现、可自愈地跑"。
- 云原生把运维固化成**声明式配置**(Dockerfile / K8s YAML),纳入版本控制,可审计可回滚。
- 容器化三原则:**多阶段瘦身、分层缓存、非 root**;镜像用不可变 tag。
- 三探针各司其职:**liveness 查自身(失败重启)、readiness 查依赖(失败摘流量)、startup 给足启动时间**——配错比不配更危险。
- 发布靠**滚动更新(maxUnavailable=0)+ 就绪探针 + 优雅停机**做到不停机不丢请求;高风险变更走灰度。
- 配置外置(12-Factor)、密钥从 KMS 注入、可观测(Ch14)三支柱 + 审计(Ch18)一起构成生产运维底座。

至此,企业级 Knowledge Agent 的推理、治理、商业化、性能、安全、部署已全部打通。下一章 **Chapter 20 · 总结与演进路线**,回顾全景架构并给出下一步演进方向。
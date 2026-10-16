# 部署文档（DEPLOY）

> 项目：Day14 Enterprise AI WeChat Agent
> 目标环境：本地开发 -> Docker -> Kubernetes
> CI/CD：GitHub Actions

---

## 1. 环境要求

| 组件 | 版本 |
| --- | --- |
| JDK | 21（启用 Virtual Threads） |
| Maven | 3.9+ |
| Spring Boot | 4.1.0 |
| PostgreSQL | 17（+ pgvector 扩展） |
| Redis | 8 |
| Docker | 24+ |
| Kubernetes | 1.29+ |

---

## 2. 本地运行

### 2.1 编译与测试

```bash
# 编译
mvn -q -o clean compile

# 运行测试
mvn -q test

# 打包
mvn -q clean package -DskipTests
```

### 2.2 启动应用

```bash
java --enable-preview -jar target/*.jar
# 或
mvn spring-boot:run
```

默认监听 `http://localhost:8080`。

### 2.3 冒烟验证

```bash
curl -X POST http://localhost:8080/api/agent/instruct \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"acme","userId":"u1","conversationId":"c1","instruction":"生成会议纪要并发给项目组"}'
```

---

## 3. 配置项（application.yml）

```yaml
server:
  port: 8080

spring:
  threads:
    virtual:
      enabled: true          # 开启虚拟线程
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/wechat_agent
    username: ${DB_USER:agent}
    password: ${DB_PASS:changeme}
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379

wechat-agent:
  ai:
    base-url: ${AI_BASE_URL:https://api.openai.com/v1}
    api-key: ${AI_API_KEY:}     # 从环境变量注入，切勿硬编码
  webhook:
    wecom-secret: ${WECOM_SECRET:}   # 从环境变量/密钥管理注入
```

> **安全红线**：`api-key`、`wecom-secret` 等敏感信息一律通过环境变量或密钥管理系统注入，严禁硬编码进代码或提交到仓库。

---

## 4. Docker 部署

### 4.1 Dockerfile（多阶段构建）

```dockerfile
# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q clean package -DskipTests

# ---- 运行阶段 ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 4.2 构建与运行

```bash
docker build -t wechat-agent:latest .
docker run -d -p 8080:8080 \
  -e DB_HOST=host.docker.internal \
  -e AI_API_KEY=sk-xxx \
  -e WECOM_SECRET=yyy \
  --name wechat-agent wechat-agent:latest
```

---

## 5. Kubernetes 部署

### 5.1 Deployment（片段）

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: wechat-agent
spec:
  replicas: 2
  selector:
    matchLabels: { app: wechat-agent }
  template:
    metadata:
      labels: { app: wechat-agent }
    spec:
      containers:
        - name: wechat-agent
          image: registry.example.com/wechat-agent:latest
          ports:
            - containerPort: 8080
          envFrom:
            - secretRef: { name: wechat-agent-secrets }
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
```

### 5.2 Secret（敏感配置）

```bash
kubectl create secret generic wechat-agent-secrets \
  --from-literal=AI_API_KEY=sk-xxx \
  --from-literal=WECOM_SECRET=yyy \
  --from-literal=DB_PASS=changeme
```

### 5.3 Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: wechat-agent
spec:
  selector: { app: wechat-agent }
  ports:
    - port: 80
      targetPort: 8080
```

---

## 6. CI/CD（GitHub Actions）

`.github/workflows/ci.yml` 关键阶段：

```yaml
name: CI
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21 }
      - run: mvn -q clean verify
      - run: docker build -t wechat-agent:${{ github.sha }} .
```

---

## 7. 上线前检查清单

- [ ] 敏感配置全部走环境变量/Secret，无硬编码
- [ ] 数据库迁移脚本已执行（含 pgvector 扩展）
- [ ] `SUSPENDED` 任务持久化已启用（否则重启丢审批任务）
- [ ] `/api/admin` 与 `/api/agent` 已接入鉴权
- [ ] 限流与超时已配置
- [ ] 健康检查端点可用（readiness/liveness）
- [ ] 日志含 traceId，接入集中日志与监控
- [ ] 死信告警已配置

---

## 8. 运维

- **健康检查**：`/actuator/health`
- **死信监控**：管理后台 `/api/admin/dashboard` + 死信重投
- **扩缩容**：无状态设计，水平扩容 `replicas`；会话与任务状态在 PostgreSQL/Redis，Pod 可随意伸缩
- **回滚**：镜像按 `git sha` 打标，`kubectl rollout undo` 快速回滚
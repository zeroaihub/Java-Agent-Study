# DEPLOY · Enterprise Knowledge Agent V2

本文件把 Ch19 的容器化与 K8s 配置沉淀为**可执行部署手册**。目标:从零到生产,一步不缺;配置外置(12-Factor),密钥永不入库(Ch18 红线)。

---

## 一、环境依赖

| 组件 | 版本 | 用途 | 章节 |
| --- | --- | --- | --- |
| JDK | 21 | 虚拟线程运行时 | Ch17 |
| PostgreSQL + pgvector | 17 | 向量 + 元数据 | Ch05/DATABASE.md |
| Elasticsearch/OpenSearch | 8.15 | BM25 关键词检索 | Ch05 |
| Redis | 8 | 缓存/配额/限流 | Ch16/Ch17 |
| Docker | 24+ | 容器化 | Ch19 |
| Kubernetes | 1.29+ | 编排 | Ch19 |

---

## 二、本地一键起依赖

```bash
cd deploy
docker compose up -d          # 起 pg17+pgvector / es8.15 / redis8
docker compose ps             # 等 healthcheck 全部 healthy
```

初始化数据库 schema:

```bash
psql "postgres://ka:ka@localhost:5432/knowledge_agent" -f ../docs/schema/init.sql
```

> `init.sql` 内容见 DATABASE.md(建表 + pgvector 扩展 + HNSW 索引 + RLS 策略)。

---

## 三、构建镜像

多阶段 + 非 root 的 `deploy/Dockerfile`(Ch19)。构建:

```bash
docker build -t knowledge-agent:1.0.0 -f deploy/Dockerfile .
```

镜像三原则回顾:多阶段瘦身(构建层与运行层分离)、分层缓存(依赖层与代码层分开)、非 root 运行(降权)。

本地冒烟:

```bash
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=local \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/knowledge_agent \
  knowledge-agent:1.0.0
curl localhost:8080/actuator/health/readiness
```

---

## 四、配置项(全部外置,占位符见 .env.example)

| 变量 | 说明 | 敏感 |
| --- | --- | --- |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | PostgreSQL 连接 | 密码敏感 |
| `ES_URIS` | Elasticsearch 地址 | 否 |
| `REDIS_HOST` / `REDIS_PASSWORD` | Redis | 密码敏感 |
| `OPENAI_API_KEY` / `RERANK_API_KEY` | 模型密钥 | **高敏,只走 Secret** |
| `SPRING_PROFILES_ACTIVE` | 环境 profile | 否 |

> 敏感项一律走 K8s Secret / 外部密钥管理(Vault),**绝不写进镜像或 ConfigMap**(Ch18)。

---

## 五、部署到 Kubernetes

顺序应用清单(均在 `deploy/k8s/`):

```bash
kubectl apply -f deploy/k8s/configmap.yaml    # 非敏感配置
kubectl apply -f deploy/k8s/secret.yaml       # 敏感配置(生产用 sealed-secrets/Vault 注入)
kubectl apply -f deploy/k8s/deployment.yaml   # 滚动更新 + 三探针 + 资源配额
kubectl apply -f deploy/k8s/service.yaml
kubectl apply -f deploy/k8s/hpa.yaml          # 自动扩缩容
```

验证:

```bash
kubectl rollout status deployment/knowledge-agent
kubectl get pods -l app=knowledge-agent
kubectl logs -f deployment/knowledge-agent
```

### 5.1 三探针语义(Ch19)

- **startupProbe**:给足慢启动时间,成功前不触发存活检查;
- **livenessProbe**:查**自身**(只查 JVM 存活),失败→重启;**不查依赖**,否则依赖抖动引发雪崩式重启;
- **readinessProbe**:查**依赖**(DB/Redis/ES 是否可达),失败→从 Service 摘流量但不重启。

### 5.2 滚动更新(零停机)

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1
    maxUnavailable: 0    # 老实例先起新的再下旧的,不减少可用副本
```

---

## 六、优雅停机(Ch19)

- 容器收到 `SIGTERM` 后:K8s 先摘流量(readiness→不再路由新请求),再等 `terminationGracePeriodSeconds`;
- 应用侧 `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase`:**处理完在途请求再退出**;
- Dockerfile 用 **exec 形式** `ENTRYPOINT`(PID 1 是 JVM,能收到信号),避免 shell 吞信号。

---

## 七、发布策略

- **滚动更新**:默认,`maxUnavailable=0` 保可用;
- **金丝雀**:先放小流量到新版本,观测 Ch14 指标(错误率/延迟/接地率)无异常再全量;
- **回滚**:`kubectl rollout undo deployment/knowledge-agent`,秒级回退到上一稳定版本。

---

## 八、发布前检查清单(Checklist)

- [ ] 镜像非 root、已扫描漏洞;
- [ ] 所有密钥走 Secret,ConfigMap/镜像内无明文(Ch18);
- [ ] DB 迁移脚本已执行且可回滚;
- [ ] 三探针配置正确(liveness 查自身、readiness 查依赖);
- [ ] `maxUnavailable=0` + 优雅停机已启用;
- [ ] HPA 阈值与 requests/limits 合理;
- [ ] Ch13 评估集在 CI 通过(接地率/引用正确率不回退);
- [ ] 监控大盘 + 告警已就绪(Ch14),金丝雀观测指标明确;
- [ ] 回滚预案演练过。

---

## 九、故障速查

| 现象 | 排查方向 |
| --- | --- |
| Pod 反复重启 | liveness 是否误查了依赖;启动是否超 startupProbe |
| 就绪一直 false | DB/Redis/ES 连通性;凭证是否正确 |
| 首次请求慢 | 缓存冷启动(Ch17);预热 embedding/连接池 |
| 大量 429 | 配额/限流触发(Ch16),核对租户套餐 |
| 答案质量回退 | 跑 Ch13 评估集定位;检查模型/索引变更 |
# 第四章 搭建 Java AI 开发环境

> 五段式教学：**为什么学 → 是什么 → 怎么用 → 用在哪 → 避坑优化**
>
> 前三章我们建立了认知：为什么要学 Agent、LLM 底层原理、Agent 架构公式。从本章开始，我们要把"纸上谈兵"变成"真刀真枪"——先把开发环境搭起来。环境搭不好，后面所有 Demo 都跑不通，这一章看似枯燥，实则是整个训练营的地基。

---

## 第一部分：为什么学（Why）——为什么环境搭建值得单独讲一章？

作为一名资深 Java 工程师，你可能会想："不就是加个 Maven 依赖吗？我 Spring Boot 项目搭过上百个了，还用你教？"

如果这么想，你大概率会在第一天就踩坑。因为 **AI 开发环境和传统 Web 开发环境有三个本质区别**：

| 维度 | 传统 Spring Boot 项目 | Spring AI 项目 |
|------|----------------------|----------------|
| 依赖来源 | Maven Central 全都有 | 部分版本在 `spring-milestones` 里程碑仓库 |
| 版本管理 | 跟随 spring-boot-parent | 需额外引入 `spring-ai-bom` 统一版本 |
| 外部依赖 | 数据库、Redis（本地可控） | 大模型 API（需 Key、需网络、需计费） |
| 失败表现 | 编译报错、启动报错 | 编译通过、启动成功，但**调用时才报 401/超时** |

最后一行是最坑的：**你的项目能正常启动，但一调用大模型就报错**。新手常常花几个小时排查代码，最后发现是 API Key 没配对，或者网络到不了 OpenAI。

所以这一章的目标是：**让你在写任何业务代码之前，先确保"Java → Spring AI → 大模型"这条链路是通的**。这就像盖房子前先确认水电煤气进户——地基不牢，地动山摇。

---

## 第二部分：是什么（What）——AI 开发环境的五层组成

一个完整的 Java AI 开发环境由五层构成，从下到上：

```
┌─────────────────────────────────────────┐
│  第5层：大模型服务（OpenAI / 通义 / DeepSeek）│  ← 真正的"大脑"，在云端
├─────────────────────────────────────────┤
│  第4层：Spring AI（2.0.0）                 │  ← 屏蔽各家模型差异的统一 SDK
├─────────────────────────────────────────┤
│  第3层：Spring Boot（4.1.0）               │  ← 你熟悉的应用框架
├─────────────────────────────────────────┤
│  第2层：Maven                             │  ← 依赖管理与构建
├─────────────────────────────────────────┤
│  第1层：JDK 21                            │  ← 运行时基石
└─────────────────────────────────────────┘
```

逐层说明：

### 第1层：JDK 21（为什么推荐 21？）

Spring Boot 4.x 最低要求 JDK 17，官方**推荐使用 JDK 21**（虚拟线程、结构化并发、模式匹配等新特性原生支持）。原因是 Spring Boot 4 基于 Spring Framework 7，全面拥抱了：
- **Jakarta EE 11**：包名从 `javax.*` 全部改成 `jakarta.*`
- **Virtual Threads、Record、Sealed Class、Switch 模式匹配**等 JDK 21 新特性

如果你还在用 JDK 8/11/17，建议升级到 JDK 21 以获得最佳体验。

### 第2层：Maven（依赖管理）

我们用 Maven 而非 Gradle，因为大部分 Java 企业项目仍以 Maven 为主。核心概念你都熟：`pom.xml`、`dependencyManagement`、`dependencies`。唯一新增的是 **BOM（Bill of Materials，物料清单）** 的用法——后面细讲。

### 第3层：Spring Boot 4.1.0

你的老朋友，无需多言。注意版本是 **4.1.0**，对应本项目 `pom.xml` 的 parent 配置。

### 第4层：Spring AI 2.0.0（核心新成员）

这是本训练营的主角。**Spring AI 之于大模型，就像 JDBC 之于数据库**：

| 类比对象 | 作用 |
|----------|------|
| JDBC | 统一 API，屏蔽 MySQL / Oracle / PostgreSQL 差异 |
| Spring AI | 统一 API，屏蔽 OpenAI / 通义 / DeepSeek / 文心 差异 |

有了它，你写一次 `ChatClient` 代码，换模型时只需改配置，不用改代码。这就是 Spring 一贯的"约定优于配置"哲学在 AI 领域的延续。

### 第5层：大模型服务

真正干活的"大脑"在云端。我们通过 API Key 调用它，按 Token 计费。可以是 OpenAI 官方，也可以是国内兼容 OpenAI 协议的服务（如 DeepSeek、通义千问、Kimi 等）。

---

## 第三部分：怎么用（How）——手把手搭建（含每一步验证）

下面进入实操。**每一步都给出验证命令，验证不通过绝不进入下一步**——这是新手最容易偷懒也最容易翻车的地方。

### Step 1：安装并验证 JDK 21

打开终端，执行：

```bash
java -version
```

期望输出（版本号 21 开头即可）：

```
openjdk version "21.0.x" ...
OpenJDK Runtime Environment ...
```

如果显示的是 1.8、11 或 17，说明 JDK 版本不对。macOS 推荐用 SDKMAN 管理多版本：

```bash
# 安装 SDKMAN（如已装可跳过）
curl -s "https://get.sdkman.io" | bash
# 安装 JDK 21
sdk install java 21.0.6-tem
# 切换到 21
sdk use java 21.0.6-tem
```

**验证点**：`java -version` 必须显示 21。

### Step 2：验证 Maven

```bash
mvn -version
```

期望看到 Maven 版本（3.9+）以及它引用的 Java 版本是 21：

```
Apache Maven 3.9.x
Java version: 21.0.x, vendor: ...
```

**关键坑**：`mvn -version` 里的 `Java version` 必须也是 21。如果 `java -version` 是 21 但 `mvn -version` 是 8，说明 Maven 用的是 `JAVA_HOME` 环境变量指向的旧 JDK，需修正 `JAVA_HOME`。

**验证点**：两个命令的 Java 版本都是 21。

### Step 3：理解并配置 pom.xml（本项目已配好，重点是看懂）

本项目 `pom.xml` 的三个关键点，正好对应"AI 环境和普通环境的区别"：

**关键点 1：里程碑仓库（如用 Milestone 版本才需要）**

```xml
<repositories>
    <repository>
        <id>spring-milestones</id>
        <name>Spring Milestones</name>
        <url>https://repo.spring.io/milestone</url>
        <snapshots>
            <enabled>false</enabled>
        </snapshots>
    </repository>
</repositories>
```

> 说明：Spring AI 2.0.0 正式版在 Maven Central 已有，理论上不加这个仓库也行。但保留它有备无患。

**关键点 2：用 BOM 统一 Spring AI 版本**

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>  <!-- 2.0.0 -->
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

> **为什么要 BOM？** Spring AI 由几十个子模块组成（openai、ollama、vectorstore、mcp...），如果每个都手写版本号，极易版本冲突。BOM 就像一张"总清单"，声明一次版本，下面所有 Spring AI 依赖都不用再写版本号，由 BOM 统一裁决。这正是 Spring 生态的标准做法。

**关键点 3：引入具体 Starter**

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
    <!-- 注意：这里没写 version，由上面的 BOM 统一管理 -->
</dependency>
```

> `spring-ai-starter-model-openai` 是 OpenAI 模型的自动配置 Starter。引入后，Spring Boot 会自动帮你装配 `ChatClient`、`ChatModel` 等 Bean，你直接注入使用即可。这就是 Spring Boot Starter 的"开箱即用"哲学。

其余依赖（web、webflux、validation、lombok、jackson、fastjson2）都是你熟悉的常规依赖。

**验证点**：在项目根目录执行以下命令，确保依赖能全部下载：

```bash
mvn clean compile
```

看到 `BUILD SUCCESS` 即为通过。若卡在下载 Spring AI 依赖，多半是仓库或网络问题（见第五部分避坑）。

### Step 4：配置 API Key（最容易出错的一步）

大模型调用需要凭证。我们**绝不把 Key 硬编码在代码里**，而是放在配置文件。在 `src/main/resources/application.yml` 中配置：

```yaml
spring:
  ai:
    openai:
      # API Key：从环境变量读取，避免泄露到代码仓库
      api-key: ${OPENAI_API_KEY}
      # 如果用国内兼容 OpenAI 协议的服务（如 DeepSeek），改这个 base-url
      base-url: https://api.openai.com
      chat:
        options:
          # 默认模型
          model: gpt-4o-mini
          # 温度：0~2，越大越随机，问答场景建议 0.7
          temperature: 0.7
```

然后在终端设置环境变量（**不要写进代码或提交到 Git**）：

```bash
# macOS / Linux（临时生效，当前终端）
export OPENAI_API_KEY="sk-你的真实key"

# 永久生效：写入 ~/.zshrc 后 source ~/.zshrc
echo 'export OPENAI_API_KEY="sk-你的真实key"' >> ~/.zshrc
source ~/.zshrc
```

**如果你用国内模型（推荐，便宜且免翻墙）**，以 DeepSeek 为例：

```yaml
spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com   # 换成 DeepSeek 的地址
      chat:
        options:
          model: deepseek-chat             # 换成 DeepSeek 的模型名
          temperature: 0.7
```

> **核心思想**：因为 Spring AI 屏蔽了差异，且 DeepSeek 等国产模型兼容 OpenAI 协议，所以**只改配置、不改代码**就能切换模型。这就是第二部分讲的"JDBC 式统一"的威力。

**验证点**：确认 `application.yml` 里没有明文 Key，且环境变量已设置（`echo $OPENAI_API_KEY` 能打印出来）。

### Step 5：规范目录结构（为后续 Demo 打基础）

本训练营所有 Day1 代码放在 `day01foundation` 目录下，遵循企业级分层规范：

```
com.zero.ai.agentstudy.day01foundation
├── controller     # 接口层：接收 HTTP 请求
├── service        # 业务层：编排 AI 调用逻辑
├── config         # 配置层：ChatClient、线程池等 Bean 配置
├── model / dto    # 数据模型：请求/响应对象
└── util           # 工具类
```

> 这套分层你再熟悉不过，和普通 Spring Boot 项目完全一致。**AI 项目在架构上并不特殊，特殊的只是 service 层里多了"调用大模型"这一步**。这也印证了第一章的观点：Agent 工程师是"造车的"，用的还是你熟悉的工程能力。

**验证点**：目录结构建立完成，包路径正确。

---

## 第四部分：用在哪（Where）——环境配置在企业项目中的实际价值

环境搭建看似基础，但在企业项目中，**配置管理能力直接决定项目能否上生产**。结合你的个人项目场景：

### 场景 1：你的「AI 工具导航站」——多模型成本优化

导航站要给大量用户提供 AI 摘要功能。如果全用 GPT-4o，成本极高。通过 Spring AI 的配置化能力，你可以：
- 简单摘要用便宜的 `gpt-4o-mini` 或 `deepseek-chat`
- 复杂分析才切换到 `gpt-4o`

**只改配置、不改代码**，就能做成本优化。这正是本章"配置驱动"思想的价值。

### 场景 2：你的「AI 公众号自动化」——多环境隔离

- 本地开发：用便宜模型 + 低 Temperature，快速验证逻辑
- 生产环境：用高质量模型 + 通过 Spring Profile（`application-prod.yml`）隔离配置

Spring Boot 的多环境 Profile 机制在 AI 项目里同样适用，你已有的经验可直接复用。

### 场景 3：你的「AI 量化交易 Agent」——API Key 安全

量化交易涉及资金，API Key 泄露后果严重。本章强调的"**Key 走环境变量、绝不硬编码、绝不进 Git**"，在这类高敏感项目中是红线要求。企业里通常还会进一步用 **配置中心（Nacos/Apollo）或密钥管理服务（KMS）** 托管，本章的环境变量方案是最基础的第一步。

### 企业通用价值总结

| 能力 | 企业价值 |
|------|----------|
| BOM 统一版本 | 避免依赖冲突，团队协作不"版本地狱" |
| 配置化模型 | 成本可控、模型可热切换 |
| Key 环境变量化 | 安全合规，避免密钥泄露 |
| 标准分层目录 | 团队协作、可维护、可测试 |

---

## 第五部分：避坑优化（Optimization）——新手必踩的 6 个坑

这一部分是本章精华。以下 6 个坑，几乎每个初学者都会踩，提前知道能省你几小时。

### 坑 1：JDK 版本不对（最高频）

**现象**：`mvn compile` 报错 `invalid target release: 21` 或 `class file version 65.0`。

**根因**：`JAVA_HOME` 指向了 JDK 8/11/17。

**解决**：确认 `java -version` 和 `mvn -version` 里的 Java 都是 21；用 SDKMAN 切换；必要时显式设置 `JAVA_HOME`。

### 坑 2：Spring AI 依赖下载不下来

**现象**：`mvn compile` 卡在下载 `spring-ai-*`，或报 `Could not find artifact`。

**根因**：① 网络到不了仓库；② 用了 Milestone 版本却没配 `spring-milestones` 仓库。

**解决**：确认仓库配置；国内可配置阿里云 Maven 镜像加速；确认版本号（2.0.0 正式版在 Central）。

### 坑 3：API Key 无效 / 401 Unauthorized（最隐蔽）

**现象**：项目**启动成功**，但一调用大模型就报 `401` 或 `Incorrect API key`。

**根因**：这是新手最迷惑的坑——**编译和启动都不校验 Key，只有真正发起调用时才校验**。所以别再怀疑代码了，先查 Key。

**解决**：
1. `echo $OPENAI_API_KEY` 确认环境变量已设置且非空；
2. 确认 Key 没有多余空格、引号；
3. 确认 `base-url` 和 Key 是同一家（用 DeepSeek 的 Key 却配 OpenAI 的 url 必然 401）。

### 坑 4：网络超时 / Connection timeout

**现象**：调用报 `Read timed out` 或 `Connection refused`。

**根因**：OpenAI 官方 API 在国内直连困难。

**解决**：**强烈推荐国内开发者用 DeepSeek / 通义 / Kimi 等国产兼容服务**，免翻墙、便宜、稳定。只需改 `base-url` 和 `model`，代码零改动。

### 坑 5：base-url 和 model 不匹配

**现象**：报 `model not found` 或 `404`。

**根因**：换了 `base-url`（如 DeepSeek）却忘了同步改 `model`（还写着 `gpt-4o`）。

**解决**：`base-url` 和 `model` 必须成对配置，一家一套。

### 坑 6：Lombok 注解不生效

**现象**：`@Data`、`@Slf4j` 生成的方法找不到，编译报错。

**根因**：需在 `maven-compiler-plugin` 里**显式声明 Lombok 注解处理器**。

**解决**：本项目 `pom.xml` 已在 `build` 里配好 `annotationProcessorPaths`，无需额外操作。若你自建项目遇到，把这段配置抄过去即可。

### 优化建议：搭建"环境自检"清单

建议把下面这张清单贴在显示器边，每次新环境按此自检：

```
[ ] java -version 显示 21
[ ] mvn -version 的 Java 也是 21
[ ] mvn clean compile 显示 BUILD SUCCESS
[ ] echo $API_KEY 能打印出 Key
[ ] application.yml 中无明文 Key
[ ] base-url 与 model 成对匹配
```

---

## 核心知识速记

| 知识点 | 一句话记忆 |
|--------|-----------|
| JDK 版本 | Spring Boot 4 推荐 JDK 21（虚拟线程原生支持） |
| Spring AI 定位 | 大模型界的 JDBC，统一 API 屏蔽差异 |
| BOM 作用 | 一处声明版本，Spring AI 全家不用写版本号 |
| Starter 作用 | 引入即自动装配 ChatClient，开箱即用 |
| API Key | 走环境变量，绝不硬编码，绝不进 Git |
| 401 排查 | 启动成功但调用报 401 → 先查 Key，别查代码 |
| 换模型 | 只改 base-url + model，代码零改动 |
| Lombok | 需在 maven-compiler-plugin 显式声明注解处理器 |

---

## 思考题（请先自己思考，再看下方答案）

**思考题 1**：为什么 Spring AI 项目"编译成功、启动成功"却可能在调用时才报 API Key 错误？这背后的设计逻辑是什么？

**思考题 2**：如果公司要求同一套代码，在测试环境用 DeepSeek、生产环境用 GPT-4o，你会怎么设计配置？（提示：Spring Profile）

**思考题 3**：为什么要用 BOM 而不是给每个 Spring AI 依赖单独写版本号？请用你熟悉的 spring-boot-dependencies 类比说明。

---

## 常见面试题（企业视角）

**Q1：Spring Boot 4 为什么推荐 JDK 21？**
A：Spring Boot 4 基于 Spring Framework 7，采用 Jakarta EE 11，并充分利用 Virtual Threads（虚拟线程）、结构化并发、Record、Sealed Class 等 JDK 21 特性，官方推荐 JDK 21 以获得最佳性能与开发体验。

**Q2：Spring AI 的核心价值是什么？**
A：提供统一的、面向大模型的抽象（ChatClient/ChatModel/EmbeddingModel 等），屏蔽 OpenAI、通义、DeepSeek 等各家 API 差异，让业务代码与具体模型解耦，换模型只改配置。

**Q3：生产环境如何安全管理 API Key？**
A：最基础用环境变量；进阶用配置中心（Nacos/Apollo）加密存储；企业级用云厂商 KMS 密钥管理服务。核心原则：绝不硬编码、绝不进代码仓库、按环境隔离。

**Q4：什么是 Maven BOM？**
A：Bill of Materials，物料清单。通过 `dependencyManagement` 导入，集中声明一组关联依赖的版本，下游引用时无需写版本号，从根本上避免版本冲突。

---

## 本章练习答案

**思考题 1 参考答案**：
因为 Spring AI 的自动配置在启动阶段只做"Bean 装配"，并不会真正向大模型发起网络请求去校验 Key（否则每次启动都要联网、都要花钱）。Key 的有效性只有在**运行时真正调用 `ChatClient`** 时，由大模型服务端返回 401 才暴露。这是一种"懒校验/延迟校验"设计：把昂贵的、有副作用的网络调用推迟到真正需要时才执行——和 JPA 的懒加载、Spring 的 `@Lazy` Bean 是同一种工程思想。

**思考题 2 参考答案**：
用 Spring Profile 做多环境隔离：
- `application.yml` 放公共配置；
- `application-test.yml` 配 DeepSeek 的 `base-url`/`model`/`api-key`；
- `application-prod.yml` 配 GPT-4o 的对应配置；
- 启动时用 `--spring.profiles.active=prod` 或环境变量指定生效环境。
这样**同一套代码**，靠切换 Profile 就能在不同环境用不同模型，符合"配置驱动、代码不变"的原则。

**思考题 3 参考答案**：
如果给每个 Spring AI 子模块单独写版本号，一旦升级就要改几十处，还极易出现"openai 是 1.0.0、vectorstore 是 0.8.0"的版本错配，引发运行时冲突。BOM 就像 `spring-boot-dependencies`：它是一张集中的版本清单，`import` 之后，所有 Spring AI 依赖的版本都由这张清单统一裁决，我们引用时**不写版本号**即可。改版本时只需改 BOM 的一个 `${spring-ai.version}`，全家跟随，既安全又省心。

---

## 企业应用小结

环境搭建不是"配置几行 XML"这么简单，它体现的是**工程规范意识**：版本统一（BOM）、配置隔离（Profile）、密钥安全（环境变量/KMS）、分层清晰（controller/service/config）。这些能力在企业里比"会调 API"更被看重——因为**能跑起来是本事，能稳定上生产是专业**。你作为资深 Java 工程师的既有工程素养，在这里几乎可以 100% 复用，这也是 Java 程序员转型 AI Agent 的独特优势。

---

> ✅ **本章完成。** 下一章《完成你的第一个 AI Demo》，我们将基于本章搭好的环境，写出第一个真正能对话的 AI 程序，包含普通聊天、System Prompt 设定、模型参数控制、异常处理、日志记录五大完整功能，并给出可直接运行的 Controller / Service / Config / DTO 全套代码。
>
> **请先完成本章三道思考题，思考完毕后告诉我，我们继续第五章。**
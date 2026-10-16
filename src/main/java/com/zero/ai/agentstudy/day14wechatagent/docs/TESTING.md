# 测试文档（TESTING）

> 项目：Day14 Enterprise AI WeChat Agent
> 测试栈：JUnit 5 / Mockito / Spring Boot Test / Testcontainers / ArchUnit

---

## 1. 测试金字塔

```
            /\
           /  \      端到端 E2E (少)     全链路: instruct -> approve -> COMPLETED
          /----\
         /      \    集成测试 (适量)      Controller + Service + 内存/容器仓储
        /--------\
       /          \  单元测试 (多)        领域模型、编排器、纯逻辑
      /------------\
```

- **单元测试（多）**：领域模型、编排器 `toReply` 状态映射、Planner 规则等纯逻辑，Mock 掉外部依赖。
- **集成测试（适量）**：Controller + Service 联调，仓储用内存实现或 Testcontainers 起真实 PostgreSQL/Redis。
- **端到端（少）**：跑通「下指令 -> 挂起 -> 审批放行 -> 完成」的完整链路。

---

## 2. 运行测试

```bash
# 全量测试
mvn -q test

# 单个类
mvn -q -Dtest=MeetingSummaryOrchestratorTest test

# 集成测试（需 Docker 运行 Testcontainers）
mvn -q verify
```

---

## 3. 单元测试示例：编排器状态映射

```java
class MeetingSummaryOrchestratorTest {

    SessionService sessionService = mock(SessionService.class);
    WorkflowEngine workflowEngine = mock(WorkflowEngine.class);
    MeetingSummaryOrchestrator orchestrator =
            new MeetingSummaryOrchestrator(sessionService, workflowEngine);

    @Test
    void handle_whenSuspended_shouldReturnPendingStepId() {
        // given
        var session = ConversationSession.create("acme", ChannelType.WECOM, "c1");
        when(sessionService.getOrCreate("acme", ChannelType.WECOM, "c1")).thenReturn(session);

        WorkflowTask suspended = ... // 构造一个 SUSPENDED 且含 WAITING_APPROVAL 步骤的任务
        when(workflowEngine.planAndRun(anyString(), any())).thenReturn(suspended);

        // when
        var reply = orchestrator.handle("acme", "u1", "c1", "生成纪要并发送");

        // then
        assertThat(reply.status()).isEqualTo("SUSPENDED");
        assertThat(reply.pendingStepId()).isNotNull();
        verify(sessionService).touch(session);
    }

    @Test
    void handle_whenCompleted_shouldReturnCompletedMessage() {
        // ... 构造 COMPLETED 任务，断言 reply.message 含「已完成」
    }
}
```

**要点**：编排器只依赖 `SessionService` 与 `WorkflowEngine` 两个可 Mock 的服务，单测极易编写——这正是「依赖面最小化」带来的可测性红利。

---

## 4. 集成测试示例：AgentController

```java
@SpringBootTest
@AutoConfigureMockMvc
class AgentControllerIT {

    @Autowired MockMvc mockMvc;

    @Test
    void instruct_missingTenant_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/agent/instruct")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":"u1","conversationId":"c1","instruction":"x"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void instruct_valid_shouldReturnTaskId() throws Exception {
        mockMvc.perform(post("/api/agent/instruct")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"tenantId":"acme","userId":"u1","conversationId":"c1",
                     "instruction":"生成会议纪要并发给项目组"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.taskId").exists());
    }
}
```

---

## 5. 端到端测试：全链路审批流

覆盖终极场景最关键路径：

```java
@Test
void fullFlow_instruct_suspend_approve_complete() {
    // 1. 下指令 -> 命中审批 -> SUSPENDED
    var reply1 = orchestrator.handle("acme", "u1", "c1", "生成纪要发项目组存知识库");
    assertThat(reply1.status()).isEqualTo("SUSPENDED");
    String taskId = reply1.taskId();
    String stepId = reply1.pendingStepId();

    // 2. 人工放行 -> 从挂起点续跑 -> COMPLETED
    var reply2 = orchestrator.approve(taskId, stepId);
    assertThat(reply2.status()).isEqualTo("COMPLETED");
}

@Test
void fullFlow_reject_shouldFail() {
    var reply1 = orchestrator.handle("acme", "u1", "c1", "生成纪要发项目组");
    var reply2 = orchestrator.reject(reply1.taskId(), reply1.pendingStepId(), "内容需修改");
    assertThat(reply2.status()).isEqualTo("FAILED");
}
```

---

## 6. 架构测试（ArchUnit）

守护架构约束，防止腐化：

```java
@AnalyzeClasses(packages = "com.zero.ai.agentstudy.day14wechatagent")
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_should_not_depend_on_spring =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    @ArchTest
    static final ArchRule agent_should_not_be_depended_on =
        noClasses().that().resideOutsideOfPackage("..wechatagent..")
            .should().dependOnClassesThat().resideInAPackage("..wechatagent..");
}
```

分别守护「领域端口不依赖 Spring」与「编排层是被依赖树的根，不被反向依赖」两条铁律。

---

## 7. 测试规范

- **一个测试文件逐个编写、逐个编译通过**后再写下一个。
- 单测不连数据库/网络，用 Mock；集成测试用 Testcontainers 起真实中间件。
- 每个测试独立、可重复，不依赖执行顺序。
- 覆盖关键分支：SUSPENDED / COMPLETED / FAILED 三态与参数校验失败。
- CI 中 `mvn verify` 必须全绿方可合并。

---

## 8. 覆盖率目标

| 层 | 目标行覆盖 |
| --- | --- |
| 领域模型 / 编排器 | ≥ 85% |
| 应用服务 | ≥ 75% |
| Controller | 关键路径 + 校验分支全覆盖 |

> 覆盖率是手段不是目的——优先覆盖「状态流转、审批分支、多租户隔离、幂等」这些高价值路径。
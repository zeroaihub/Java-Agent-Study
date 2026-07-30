package com.zero.ai.agentstudy.day11humanintheloop.approvalapi.config;

import com.zero.ai.agentstudy.day11humanintheloop.approvalengine.ApprovalEngine;
import com.zero.ai.agentstudy.day11humanintheloop.approvalengine.DefaultApprovalEngine;
import com.zero.ai.agentstudy.day11humanintheloop.approvalengine.repository.ApprovalRepository;
import com.zero.ai.agentstudy.day11humanintheloop.approvalengine.repository.InMemoryApprovalRepository;
import com.zero.ai.agentstudy.day11humanintheloop.feedbackengine.FeedbackEngine;
import com.zero.ai.agentstudy.day11humanintheloop.feedbackengine.FeedbackLearningService;
import com.zero.ai.agentstudy.day11humanintheloop.feedbackengine.FeedbackRepository;
import com.zero.ai.agentstudy.day11humanintheloop.feedbackengine.InMemoryFeedbackRepository;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.spi.DefaultRiskPolicy;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.spi.RiskPolicy;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.statemachine.ApprovalStateMachine;
import com.zero.ai.agentstudy.day11humanintheloop.multilevelapproval.MultiLevelApprovalService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Human-in-the-loop 模块的 Spring 装配中心（Composition Root）。
 *
 * <p>前七章我们刻意让所有核心类都是「纯 POJO + 构造器注入」，不带任何 Spring 注解——
 * 这是为了让领域层与框架解耦（领域代码不该知道 Spring 的存在）。到了 API 层，才由这个
 * 配置类统一「组装」：把 RiskPolicy、StateMachine、Repository 这些零件按依赖关系
 * 拼成可用的引擎 Bean，交给 Spring 容器管理。</p>
 *
 * <p>这种「领域层纯净 + 配置层集中装配」的组织方式，就是六边形架构里
 * <b>Composition Root（组装根）</b> 的实践：所有依赖关系的连线只发生在这一个地方，
 * 一眼就能看清整个模块由哪些组件、以什么关系构成。</p>
 *
 * <p><b>教学期实现说明：</b>仓储用内存实现（重启即丢），生产环境把
 * {@link #approvalRepository()} / {@link #feedbackRepository()} 换成
 * JPA / MyBatis / Redis 实现即可，其余 Bean 一行都不用改——这正是依赖倒置
 * （面向接口装配）带来的可替换性。</p>
 */
@Configuration
public class HumanLoopConfig {

    // ---------------- 基础零件 ----------------

    /** 风险策略：评估动作风险等级、决定审批级数。 */
    @Bean
    public RiskPolicy riskPolicy() {
        return new DefaultRiskPolicy();
    }

    /** 审批状态机：全模块唯一的「状态流转规则来源」，无状态、线程安全。 */
    @Bean
    public ApprovalStateMachine approvalStateMachine() {
        return new ApprovalStateMachine();
    }

    /** 审批请求仓储（出站端口）。教学期内存实现，生产替换为持久化实现。 */
    @Bean
    public ApprovalRepository approvalRepository() {
        return new InMemoryApprovalRepository();
    }

    /** 反馈仓储（出站端口）。教学期内存实现。 */
    @Bean
    public FeedbackRepository feedbackRepository() {
        return new InMemoryFeedbackRepository();
    }

    // ---------------- 用例入口（Inbound Ports） ----------------

    /** 单级审批引擎。 */
    @Bean
    public ApprovalEngine approvalEngine(RiskPolicy riskPolicy,
                                         ApprovalStateMachine stateMachine,
                                         ApprovalRepository approvalRepository) {
        return new DefaultApprovalEngine(riskPolicy, stateMachine, approvalRepository);
    }

    /** 多级会签审批服务。 */
    @Bean
    public MultiLevelApprovalService multiLevelApprovalService(ApprovalStateMachine stateMachine,
                                                               ApprovalRepository approvalRepository) {
        return new MultiLevelApprovalService(stateMachine, approvalRepository);
    }

    /** 反馈收集引擎。 */
    @Bean
    public FeedbackEngine feedbackEngine(FeedbackRepository feedbackRepository) {
        return new FeedbackEngine(feedbackRepository);
    }

    /** 反馈学习服务（从纠正类反馈提炼 few-shot 示例）。 */
    @Bean
    public FeedbackLearningService feedbackLearningService(FeedbackRepository feedbackRepository) {
        return new FeedbackLearningService(feedbackRepository);
    }
}
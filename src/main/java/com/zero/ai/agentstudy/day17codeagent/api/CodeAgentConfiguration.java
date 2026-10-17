package com.zero.ai.agentstudy.day17codeagent.api;

import com.zero.ai.agentstudy.day17codeagent.generator.CodeModel;
import com.zero.ai.agentstudy.day17codeagent.generator.StubCodeModel;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;

/**
 * Code Agent 装配点：整个模块<b>唯一</b>出现「把哪些实现组合在一起」的地方。
 *
 * <p>业务模块（context / patch / sandbox / verify / iterate / safety / review /
 * gitops / eval / observability / metering / orchestrator）全部零框架依赖，
 * 换实现只改这里：</p>
 * <ul>
 *     <li>换模型：把 {@code StubCodeModel} 换成 Spring AI 的 {@code ChatClient} 适配器；</li>
 *     <li>换仓库：把 {@code VirtualFileSystem.temp(...)} 换成真实 git worktree；</li>
 *     <li>换沙箱：把 {@code ProcessSandbox} 换成容器执行器。</li>
 * </ul>
 *
 * <p><b>为什么值得这么麻烦</b>：Code Agent 是最容易被「换个模型就全盘重写」业务拖垮的系统。
 * 把变化点收敛到一个文件，是它能长期演进的前提。</p>
 *
 * @author ZeroAi
 */
public class CodeAgentConfiguration {

    /** 演示用仓库（真实场景应挂载到 git worktree）。 */
    public static VirtualFileSystem demoRepository() {
        return VirtualFileSystem.temp("code-agent-service");
    }

    /**
     * 模型 Bean。
     *
     * <p>默认使用 {@link StubCodeModel}：零依赖、可离线、输出确定。
     * 接入真实模型时，只需在这里替换实现——其余模块一行都不用改。</p>
     */
    public static CodeModel codeModel() {
        return StubCodeModel.reliable();
    }

    public static CodeAgentController codeAgentController() {
        return new CodeAgentController(codeModel(), demoRepository());
    }
}

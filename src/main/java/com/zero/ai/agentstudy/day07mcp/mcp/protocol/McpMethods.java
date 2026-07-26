package com.zero.ai.agentstudy.day07mcp.mcp.protocol;

/**
 * McpMethods —— MCP 协议中标准方法名（method）的集中定义。
 *
 * <p>教学要点：把「魔法字符串」集中到一处常量类，避免在 Client/Server 两端
 * 各写一遍字符串导致拼写不一致（一端写 "tools/list"，另一端写 "tool/list" 就 GG）。
 * 这是消除「重复魔法值」坏味道的标准做法。</p>
 *
 * <p>MCP 规范里方法名用「命名空间/动作」的斜杠风格，如 tools/list、tools/call、
 * resources/list、prompts/list 等。本项目 Day07 只实现 initialize 与 tools/* 部分。</p>
 *
 * @author ZeroAi
 */
public final class McpMethods {

    private McpMethods() {
        // 工具类，禁止实例化
    }

    /** 初始化握手：Client 声明协议版本与能力，Server 回应自身能力 */
    public static final String INITIALIZE = "initialize";

    /** 初始化完成通知：握手结束，可以开始正常交互 */
    public static final String INITIALIZED = "notifications/initialized";

    /** 列出工具清单（工具发现） */
    public static final String TOOLS_LIST = "tools/list";

    /** 调用某个工具 */
    public static final String TOOLS_CALL = "tools/call";

    /** 列出资源（Day07 未展开，占位保留） */
    public static final String RESOURCES_LIST = "resources/list";

    /** 列出提示词模板（Day07 未展开，占位保留） */
    public static final String PROMPTS_LIST = "prompts/list";
}
package com.zero.ai.agentstudy.day17codeagent.core.common;

/**
 * Code Agent 统一错误码。
 *
 * <p>错误码采用「模块前缀 + 序号」，便于从一条日志快速定位问题归属模块：</p>
 * <ul>
 *     <li>CA-0xxx：通用 / 核心</li>
 *     <li>CA-1xxx：Repository / VirtualFileSystem（仓库读写）</li>
 *     <li>CA-2xxx：Tools（工具调用）</li>
 *     <li>CA-3xxx：Analysis / Context（静态分析 / 上下文选择）</li>
 *     <li>CA-4xxx：Patch（补丁解析与应用）</li>
 *     <li>CA-5xxx：Generator（代码生成）</li>
 *     <li>CA-6xxx：Sandbox（沙箱执行）</li>
 *     <li>CA-7xxx：Verify / Iterate（编译测试验证 / 自修复循环）</li>
 *     <li>CA-8xxx：Safety / Review（安全护栏 / 代码评审）</li>
 *     <li>CA-9xxx：Eval / Observability / Metering（评测 / 观测 / 计量）</li>
 * </ul>
 *
 * @author ZeroAi
 */
public enum CodeErrorCode {

    // ---------- 通用 ----------
    INTERNAL_ERROR("CA-0001", "系统内部错误"),
    INVALID_ARGUMENT("CA-0002", "参数非法"),
    RESOURCE_NOT_FOUND("CA-0003", "资源不存在"),
    OPERATION_NOT_ALLOWED("CA-0004", "操作不被允许"),
    TENANT_REQUIRED("CA-0005", "缺少租户上下文"),
    CONTEXT_INVALID("CA-0006", "任务上下文不完整"),

    // ---------- Repository / VFS ----------
    FILE_NOT_FOUND("CA-1001", "文件不存在"),
    FILE_ALREADY_EXISTS("CA-1002", "文件已存在"),
    FILE_TOO_LARGE("CA-1003", "文件超出体积上限"),
    PATH_TRAVERSAL("CA-1004", "检测到路径穿越，禁止越权读写"),
    PATH_OUT_OF_ROOT("CA-1005", "路径不在工作区根目录之内"),
    FILE_READ_ONLY("CA-1006", "文件只读，禁止写入"),

    // ---------- Tools ----------
    TOOL_NOT_FOUND("CA-2001", "未注册的工具"),
    TOOL_ARGUMENT_INVALID("CA-2002", "工具参数非法"),
    TOOL_EXECUTION_FAILED("CA-2003", "工具执行失败"),
    TOOL_TIMEOUT("CA-2004", "工具执行超时"),
    TOOL_DENIED("CA-2005", "工具调用被安全策略拒绝"),

    // ---------- Analysis / Context ----------
    PARSE_FAILED("CA-3001", "源码解析失败"),
    SYMBOL_NOT_FOUND("CA-3002", "未找到目标符号"),
    CONTEXT_OVERFLOW("CA-3003", "上下文超出预算"),
    REPO_MAP_EMPTY("CA-3004", "仓库地图为空"),

    // ---------- Patch ----------
    PATCH_PARSE_FAILED("CA-4001", "补丁解析失败"),
    PATCH_NOT_APPLIED("CA-4002", "补丁未能应用（上下文不匹配）"),
    PATCH_CONFLICT("CA-4003", "补丁冲突"),
    PATCH_AMBIGUOUS("CA-4004", "补丁目标不唯一"),
    PATCH_EMPTY("CA-4005", "补丁内容为空"),

    // ---------- Generator ----------
    GENERATION_FAILED("CA-5001", "代码生成失败"),
    GENERATION_OUTPUT_INVALID("CA-5002", "模型输出不符合约定格式"),
    PROMPT_TOO_LONG("CA-5003", "提示词超出模型上下文窗口"),

    // ---------- Sandbox ----------
    SANDBOX_TIMEOUT("CA-6001", "沙箱执行超时"),
    SANDBOX_OOM("CA-6002", "沙箱内存超限"),
    SANDBOX_OUTPUT_OVERFLOW("CA-6003", "沙箱输出超出行数上限"),
    SANDBOX_COMMAND_DENIED("CA-6004", "沙箱拒绝执行危险命令"),

    // ---------- Verify / Iterate ----------
    COMPILE_FAILED("CA-7001", "编译失败"),
    COMPILER_UNAVAILABLE("CA-7002", "当前运行环境没有可用的编译器"),
    TEST_FAILED("CA-7003", "测试未通过"),
    REPAIR_EXHAUSTED("CA-7004", "自修复重试次数已用尽"),
    REPAIR_NO_PROGRESS("CA-7005", "自修复没有取得实质进展"),

    // ---------- Safety / Review ----------
    DANGEROUS_COMMAND("CA-8001", "检测到危险命令"),
    SECRET_LEAKED("CA-8002", "检测到疑似密钥泄露"),
    REVIEW_REJECTED("CA-8003", "代码评审未通过"),
    LICENSE_VIOLATION("CA-8004", "许可证校验未通过"),

    // ---------- Eval / Observability / Metering ----------
    EVALUATION_FAILED("CA-9001", "评估执行失败"),
    BUDGET_EXCEEDED("CA-9002", "预算已超支"),
    QUOTA_EXCEEDED("CA-9003", "配额已用尽"),
    TRACE_WRITE_FAILED("CA-9004", "追踪写入失败");

    private final String code;
    private final String defaultMessage;

    CodeErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    @Override
    public String toString() {
        return code + " " + defaultMessage;
    }
}

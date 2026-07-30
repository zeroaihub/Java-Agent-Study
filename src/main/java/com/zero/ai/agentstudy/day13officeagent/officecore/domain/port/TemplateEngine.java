package com.zero.ai.agentstudy.day13officeagent.officecore.domain.port;

import java.util.Map;

/**
 * 模板引擎端口（TemplateEngine）——出站端口，负责"模板 + 数据 → 成品文本"的渲染。
 *
 * <p><b>为什么需要它：</b> 企业办公里大量文档是"半固定"的——周报、合同、通知、邮件正文，
 * 骨架年年不变，变的只是当期数据。让大模型每次从零生成既贵又不稳定，正确姿势是：
 * <b>固定骨架用模板，可变部分用占位符，运行时把数据填进去</b>。模板引擎就是这一步的抽象。</p>
 *
 * <p><b>为什么做成端口：</b> 模板语法百花齐放（Freemarker、Thymeleaf、Mustache、纯占位符）。
 * 把渲染能力抽象成端口，上层只依赖"给模板和数据、拿渲染结果"这一契约，具体用哪种引擎由适配器决定，
 * 未来切换引擎<b>零改动业务代码</b>。默认适配器用零依赖的 {@code ${key}} 占位符替换，规避引擎版本风险；
 * 若团队需要循环、条件等复杂逻辑，可另写 Freemarker 适配器替换，端口契约不变。</p>
 *
 * @author zero
 */
public interface TemplateEngine {

    /**
     * 用数据模型渲染模板字符串。
     *
     * <p>占位符形如 <code>${key}</code>，渲染时替换为 {@code model} 中对应键的值（调用其
     * {@code toString()}）。未在模型中出现的占位符按 {@code strict} 策略处理：严格模式抛异常，
     * 宽松模式原样保留，便于分阶段填充。</p>
     *
     * @param template 含占位符的模板文本
     * @param model    数据模型（键 → 值）
     * @param strict   遇到未知占位符是否报错
     * @return 渲染后的成品文本
     */
    String render(String template, Map<String, Object> model, boolean strict);

    /**
     * 宽松模式渲染：未知占位符原样保留，便于多阶段逐步填充。
     *
     * @param template 含占位符的模板文本
     * @param model    数据模型
     * @return 渲染后的文本
     */
    default String render(String template, Map<String, Object> model) {
        return render(template, model, false);
    }

    /**
     * 校验模板中的占位符是否都能被数据模型满足。
     *
     * <p>在真正渲染前做一次"缺参体检"，把"漏填某个字段"的问题在早期暴露，而不是等成品文档里
     * 出现一个刺眼的 <code>${amount}</code> 才被客户发现。</p>
     *
     * @param template 模板文本
     * @param model    数据模型
     * @return 校验结果（是否齐全 + 缺失的键）
     */
    ValidationResult validate(String template, Map<String, Object> model);

    /**
     * 模板校验结果值对象。
     *
     * @param valid      占位符是否全部被满足
     * @param missingKeys 缺失的占位符键（有序、去重）
     * @author zero
     */
    record ValidationResult(boolean valid, java.util.List<String> missingKeys) {

        public ValidationResult {
            missingKeys = missingKeys == null ? java.util.List.of() : java.util.List.copyOf(missingKeys);
        }
    }
}
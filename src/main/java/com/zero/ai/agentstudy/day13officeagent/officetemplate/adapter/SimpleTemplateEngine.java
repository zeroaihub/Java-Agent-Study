package com.zero.ai.agentstudy.day13officeagent.officetemplate.adapter;

import com.zero.ai.agentstudy.day13officeagent.officecore.domain.port.TemplateEngine;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简单模板引擎（SimpleTemplateEngine）——{@link TemplateEngine} 的默认零依赖适配器。
 *
 * <p><b>设计取舍：</b> 不引入 Freemarker/Thymeleaf 等模板引擎，而是用一个正则做 <code>${key}</code>
 * 占位符替换。理由与日历模块"手工 RFC 5545 文本"一脉相承——<b>稳定规范优于易变库 API</b>：
 * 占位符替换语义三十年不变、零版本风险、零传递依赖，覆盖了企业办公 80% 的"半固定文档"诉求
 * （周报、通知、合同抬头、邮件正文）。若确需循环、条件、嵌套对象，团队可另写 Freemarker 适配器
 * 替换本实现，上层依赖的 {@link TemplateEngine} 端口契约完全不变。</p>
 *
 * <p><b>占位符语法：</b> <code>${key}</code>，key 由字母、数字、下划线、点号组成（点号支持
 * <code>${user.name}</code> 这类扁平键名，但本实现按整串键在 model 中查找，不做对象导航）。
 * {@code null} 值统一渲染为空串，避免文档里出现刺眼的 "null"。</p>
 *
 * @author zero
 */
@Component
public class SimpleTemplateEngine implements TemplateEngine {

    /** 占位符模式：${ 允许内部空白，键名支持字母数字下划线点号。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{\\s*([A-Za-z0-9_.]+)\\s*}");

    @Override
    public String render(String template, Map<String, Object> model, boolean strict) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Map<String, Object> safeModel = model == null ? Map.of() : model;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 32);
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement;
            if (safeModel.containsKey(key)) {
                Object value = safeModel.get(key);
                replacement = value == null ? "" : value.toString();
            } else if (strict) {
                throw new IllegalArgumentException("模板渲染缺少占位符数据：" + key);
            } else {
                // 宽松模式：未知占位符原样保留，便于多阶段逐步填充。
                replacement = matcher.group(0);
            }
            // 转义 $ 与 \，避免被 appendReplacement 当作分组引用。
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    @Override
    public ValidationResult validate(String template, Map<String, Object> model) {
        Set<String> required = extractKeys(template);
        Map<String, Object> safeModel = model == null ? Map.of() : model;
        List<String> missing = required.stream()
                .filter(key -> !safeModel.containsKey(key))
                .toList();
        return new ValidationResult(missing.isEmpty(), missing);
    }

    /**
     * 抽取模板中出现的全部占位符键（有序去重）。
     *
     * @param template 模板文本
     * @return 占位符键集合
     */
    private Set<String> extractKeys(String template) {
        Set<String> keys = new LinkedHashSet<>();
        if (template == null || template.isEmpty()) {
            return keys;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }
}
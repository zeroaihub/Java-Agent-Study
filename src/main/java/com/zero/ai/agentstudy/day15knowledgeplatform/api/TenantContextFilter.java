package com.zero.ai.agentstudy.day15knowledgeplatform.api;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.tenant.TenantContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 租户上下文过滤器 —— <b>多租户的入口闸门</b>。
 *
 * <p>它做三件事，缺一不可：</p>
 * <ol>
 *     <li><b>解析租户</b>：从认证信息中取出租户；</li>
 *     <li><b>注入上下文</b>：放入 {@link TenantContext}，供下游所有代码读取；</li>
 *     <li><b>finally 中清理</b>：请求结束必须清除。</li>
 * </ol>
 *
 * <p><b>第 3 步是最容易被忽视、后果也最严重的一步。</b>
 * Web 容器（Tomcat）的线程是<b>复用</b>的：处理完 A 公司的请求后，
 * 这个线程会被拿去处理 B 公司的请求。如果 ThreadLocal 没清理，
 * B 的请求就会带着 A 的租户上下文运行——<b>B 公司的人看到了 A 公司的数据</b>。
 * 这是教科书级的多租户事故，而且它只在并发量上来后才偶发，
 * 本地测试永远复现不了。</p>
 *
 * <p><b>关于租户来源（重要安全说明）</b>：
 * 本实现从请求头 {@code X-Tenant-Id} 读取，<b>这是为了演示与联调方便，
 * 不构成安全实现</b>——客户端可以随意伪造这个头，等于租户隔离形同虚设。
 * <b>生产环境必须从认证凭据中推断租户</b>：
 * JWT claims、OAuth2 的租户绑定关系、或 API Key 对应的服务端记录。
 * 原则是：<b>租户只能来自服务端可验证的信息，绝不能来自客户端可以自由指定的字段。</b></p>
 *
 * @author ZeroAi
 */
public class TenantContextFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    /** 租户头（仅用于演示，生产请改为从认证信息推断）。 */
    public static final String TENANT_HEADER = "X-Tenant-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        String tenantValue = resolveTenant(httpRequest);
        try {
            if (tenantValue != null && !tenantValue.isBlank()) {
                TenantContext.set(TenantId.of(tenantValue.trim()));
            }
            chain.doFilter(request, response);
        } finally {
            // 必须清理：容器线程复用，不清理会导致租户串号（跨租户数据泄漏）
            TenantContext.clear();
        }
    }

    /**
     * 解析租户标识。
     *
     * <p>生产实现应改为：
     * <pre>
     *   Authentication auth = SecurityContextHolder.getContext().getAuthentication();
     *   return ((TenantAwarePrincipal) auth.getPrincipal()).getTenantId();
     * </pre>
     */
    private String resolveTenant(HttpServletRequest request) {
        String header = request.getHeader(TENANT_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        // 未携带租户头时不设置上下文，由下游 require() 抛出 TENANT_REQUIRED。
        // 这里刻意不抛异常：健康检查、静态资源等端点不需要租户上下文。
        log.debug("请求 {} 未携带 {}，未设置租户上下文", request.getRequestURI(), TENANT_HEADER);
        return null;
    }
}

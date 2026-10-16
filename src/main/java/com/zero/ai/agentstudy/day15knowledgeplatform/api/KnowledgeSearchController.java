package com.zero.ai.agentstudy.day15knowledgeplatform.api;

import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Principal;
import com.zero.ai.agentstudy.day15knowledgeplatform.api.dto.SearchRequest;
import com.zero.ai.agentstudy.day15knowledgeplatform.api.dto.SearchResponse;
import com.zero.ai.agentstudy.day15knowledgeplatform.application.KnowledgePlatformFacade;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeResult;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 统一检索 API —— <b>知识中台最主要的对外接口</b>。
 *
 * <p>这个接口是 Day15 全部价值的交付点：
 * Office Agent、企业微信 Agent、客服机器人、搜索 Agent，
 * 都通过它来获取企业知识。<b>一套知识，多处消费</b>——
 * 这正是「平台」与「单点功能」的区别。</p>
 *
 * <p><b>接口设计上有三处刻意的选择</b>：</p>
 * <ol>
 *     <li><b>用 POST 而不是 GET</b>：查询文本可能很长（用户粘贴一整段问题），
 *         放在 URL 里会超出长度限制，而且查询内容属于敏感信息，
 *         不应出现在访问日志里。</li>
 *     <li><b>不接受 tenantId</b>：租户来自认证上下文，
 *         客户端无法指定，从根源上消除越权。</li>
 *     <li><b>权限不足时返回 200 + 空结果</b>，而不是 403：
 *         见 {@link com.zero.ai.agentstudy.day15knowledgeplatform.acl.AclService} 的说明，
 *         「没有权限看到任何东西」是一个正常的查询结果。
 *         返回 403 会让调用方把它当成调用错误，从而触发无意义的重试与告警。</li>
 * </ol>
 *
 * @author ZeroAi
 */
@RestController
@RequestMapping("/api/knowledge/search")
public class KnowledgeSearchController {

    private final KnowledgePlatformFacade facade;
    private final RequestPrincipalResolver principalResolver;

    public KnowledgeSearchController(KnowledgePlatformFacade facade,
                                     RequestPrincipalResolver principalResolver) {
        this.facade = facade;
        this.principalResolver = principalResolver;
    }

    /**
     * 权限感知的混合检索。
     *
     * @param request  检索请求
     * @param http    原始请求（用于解析访问主体）
     * @return 统一结果包装
     */
    @PostMapping
    public KnowledgeResult<SearchResponse> search(@Valid @RequestBody SearchRequest request,
                                                  HttpServletRequest http) {
        Principal principal = principalResolver.resolve(http);

        Set<KnowledgeBaseId> scope = new LinkedHashSet<>();
        for (String kbId : request.knowledgeBaseIds()) {
            scope.add(KnowledgeBaseId.of(kbId));
        }

        var outcome = facade.search(principal, request.query(), scope, request.effectiveTopN());
        return KnowledgeResult.ok(SearchResponse.from(outcome));
    }
}

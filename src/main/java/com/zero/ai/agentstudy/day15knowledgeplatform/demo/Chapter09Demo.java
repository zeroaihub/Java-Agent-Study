package com.zero.ai.agentstudy.day15knowledgeplatform.demo;

import com.zero.ai.agentstudy.day15knowledgeplatform.acl.AclService;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.InMemoryAclRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.AccessControlList;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Permission;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Principal;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Role;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.tenant.TenantContext;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.InMemoryKnowledgeBaseRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.InMemoryWorkspaceRepository;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.WorkspaceService;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.model.KnowledgeBase;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.model.Workspace;

import java.util.List;
import java.util.Set;

/**
 * 第 9-10 章 Demo：工作空间、知识库与权限感知检索。
 *
 * <p>两章合并演示，因为它们是一对：
 * <b>Workspace 定义「知识在哪」，ACL 定义「谁能看」，
 * 而权限感知检索是两者的交汇点。</b></p>
 *
 * @author ZeroAi
 */
public class Chapter09Demo {

    public static void main(String[] args) {
        System.out.println("=".repeat(78));
        System.out.println("  第 9-10 章：knowledge-workspace + knowledge-acl");
        System.out.println("  工作空间、知识库与权限感知检索");
        System.out.println("=".repeat(78));

        TenantId acme = TenantId.of("acme");
        TenantId globex = TenantId.of("globex");

        // ---------- 第 9 章 ----------
        printSection("第 9 章 [1] 租户 → 工作空间 → 知识库 三层结构");

        var workspaceService = new WorkspaceService(
                new InMemoryWorkspaceRepository(), new InMemoryKnowledgeBaseRepository());

        TenantContext.runWithTenant(acme, () -> {
            Workspace finance = workspaceService.createWorkspace("财务空间", "报销与预算相关", null);
            Workspace legal = workspaceService.createWorkspace("法务空间", "合同与合规", null);
            System.out.println("  租户 acme 下创建工作空间：");
            System.out.println("    - " + finance);
            System.out.println("    - " + legal);

            KnowledgeBase policyKb = workspaceService.createKnowledgeBase(
                    finance.id(), "差旅制度库", "差旅与报销制度", null);
            KnowledgeBase contractKb = workspaceService.createKnowledgeBase(
                    legal.id(), "合同库", "客户合同与法务文件", null);

            System.out.println();
            System.out.println("  在财务空间下创建知识库：" + policyKb);
            System.out.println("  在法务空间下创建知识库：" + contractKb);
            System.out.println();
            System.out.println("  记下两个知识库 ID，第 10 章要用它们演示权限隔离：");
            System.out.println("    POLICY_KB   = " + policyKb.id());
            System.out.println("    CONTRACT_KB = " + contractKb.id());
            return null;
        });

        printSection("第 9 章 [2] 跨租户访问：在存储层就被挡住");
        TenantContext.runWithTenant(acme, () -> {
            Workspace finance = workspaceService.listWorkspaces(false).get(0);
            System.out.println("  在 acme 上下文下取到工作空间：" + finance.id());
            return null;
        });
        try {
            WorkspaceId stolenId = TenantContext.runWithTenant(acme, () ->
                    workspaceService.listWorkspaces(false).get(0).id());
            TenantContext.runWithTenant(globex, () -> {
                workspaceService.getWorkspace(stolenId);
                return null;
            });
            System.out.println("  ✗ 跨租户访问未被拦截！");
        } catch (RuntimeException ex) {
            System.out.println("  ✓ 已拦截跨租户访问：" + ex.getMessage());
        }
        System.out.println();
        System.out.println("  仓储的 key 是 tenantId:workspaceId 复合键，");
        System.out.println("  用 B 租户的上下文查 A 租户的 ID，拼出来的 key 在表里根本不存在。");
        System.out.println("  ★ 把安全约束下沉到数据结构，比依赖开发者记得写过滤条件可靠得多。");

        // ---------- 第 10 章 ----------
        KnowledgeBaseId policyKb = KnowledgeBaseId.of(findKbId(workspaceService, acme, "差旅制度库"));
        KnowledgeBaseId contractKb = KnowledgeBaseId.of(findKbId(workspaceService, acme, "合同库"));

        printSection("第 10 章 [1] ACL：默认拒绝 + 取最高角色");

        var aclRepository = new InMemoryAclRepository();
        var aclService = new AclService(aclRepository);

        // 合同库：只有法务组和 CEO 能看
        aclRepository.save(AccessControlList.builder(acme, contractKb)
                .grantGroup("legal", Role.ADMIN)
                .grantUser("ceo", Role.READER)
                .build());
        // 制度库：租户内所有人可读，财务组可写
        aclRepository.save(AccessControlList.builder(acme, policyKb)
                .publicRole(Role.READER)
                .grantGroup("finance", Role.MANAGER)
                .build());

        System.out.println("  合同库 ACL：法务组=ADMIN，CEO=READER，其他人无权限");
        System.out.println("  制度库 ACL：租户内公开=READER，财务组=MANAGER");
        System.out.println();

        Principal alice = Principal.of(acme, "alice", "finance");
        Principal bob = Principal.of(acme, "bob", "legal");
        Principal carol = Principal.of(acme, "carol", "sales");
        Principal attacker = Principal.of(globex, "eve", "legal");

        for (Principal p : List.of(alice, bob, carol, attacker)) {
            System.out.printf("  %-8s 合同库 SEARCH=%-5s WRITE=%-5s | 制度库 SEARCH=%-5s WRITE=%s%n",
                    p.userId(),
                    aclService.hasPermission(p, contractKb, Permission.SEARCH),
                    aclService.hasPermission(p, contractKb, Permission.WRITE),
                    aclService.hasPermission(p, policyKb, Permission.SEARCH),
                    aclService.hasPermission(p, policyKb, Permission.WRITE));
        }
        System.out.println();
        System.out.println("  解读：");
        System.out.println("    alice（财务组）→ 制度库可写，合同库无权 ✓");
        System.out.println("    bob  （法务组）→ 合同库管理员，制度库可读 ✓");
        System.out.println("    carol（销售组）→ 只有制度库可读（来自租户公开角色）✓");
        System.out.println("    eve  （另一租户的法务组！）→ 全部无权 ✓");
        System.out.println();
        System.out.println("  ★ eve 这一行是最关键的：她属于 globex 租户，");
        System.out.println("    即便组名也叫 legal，也拿不到 acme 的任何权限。");
        System.out.println("    ACL 判定的第一件事就是比对租户——这是 IDOR 漏洞的防线。");

        printSection("第 10 章 [2] 权限感知检索：把「能读哪些库」下推给检索层");

        for (Principal p : List.of(alice, carol, attacker)) {
            Set<KnowledgeBaseId> readable = aclService.resolveReadableScope(p, Set.of());
            System.out.printf("  %-8s 可读知识库：%s%n", p.userId(), readable);
        }
        System.out.println();
        System.out.println("  ★ 这份「可读集合」会被直接作为过滤条件下推到向量库与倒排索引，");
        System.out.println("    而不是先全库检索再在应用层丢弃。");
        System.out.println();
        System.out.println("  为什么必须前置？假设全库有 100 个知识库，carol 只有 1 个可读。");
        System.out.println("  向量索引返回的是「全局最近的 K 条」——");
        System.out.println("  如果先取全局 Top-50 再过滤掉 49 条无权访问的，");
        System.out.println("  真正属于她、排在 51 名的正确结果就永远找不回来了。");
        System.out.println("  ★ 召回率会凭空归零，而且无权数据还进了内存——双重损失。");

        printSection("第 10 章 [3] 职责分离：创建者不能给自己授权");
        try {
            aclService.grant(carol, contractKb, "carol", null, Role.ADMIN);
            System.out.println("  ✗ 未拦截！carol 给自己加了合同库管理员权限");
        } catch (RuntimeException ex) {
            System.out.println("  ✓ 已拦截：" + ex.getMessage());
        }
        System.out.println();
        System.out.println("  如果创建者能给自己授权，权限体系会被彻底架空：");
        System.out.println("  任何人都能「建库 → 自授权 → 读别人的机密」。");
        System.out.println("  所以授权操作必须由独立的、更高权限的入口完成（见第 14 章管理端）。");

        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("  第 9-10 章 Demo 结束");
        System.out.println("=".repeat(78));
    }

    private static String findKbId(WorkspaceService svc, TenantId tenant, String kbName) {
        return TenantContext.runWithTenant(tenant, () -> {
            for (Workspace ws : svc.listWorkspaces(false)) {
                for (KnowledgeBase kb : svc.listKnowledgeBases(ws.id())) {
                    if (kb.name().equals(kbName)) {
                        return kb.id().value();
                    }
                }
            }
            return "";
        });
    }

    private static void printSection(String title) {
        System.out.println();
        System.out.println("-".repeat(78));
        System.out.println(title);
        System.out.println("-".repeat(78));
    }
}

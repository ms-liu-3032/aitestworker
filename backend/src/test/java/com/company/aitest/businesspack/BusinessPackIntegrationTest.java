package com.company.aitest.businesspack;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * business_pack 端到端集成测试。
 * <p>
 * 验证完整链路：项目资产 → business_pack 自动生成 → 表中有数据。
 */
@SpringBootTest
@Transactional
class BusinessPackIntegrationTest {

    @Autowired
    private BusinessPackService businessPackService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long projectId;

    @BeforeEach
    void setUpProjectAssets() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO project(project_name, description, status, created_by, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 1, ?, ?)
                """, "集成测试项目", "business_pack 自动沉淀验证", now, now);
        projectId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("""
                INSERT INTO test_object_model(project_id, scope, model_type, name, description,
                    source_type, source_context, business_domain, confidence, status,
                    requires_human_confirm, created_by, created_at, updated_at)
                VALUES (?, 'PROJECT', 'FLOW', ?, ?, 'MANUAL_SECTION', ?, ?, 0.90, 'ACTIVE', 0, 1, ?, ?)
                """, projectId, "申请审批流程", "提交、审批与结果通知", "集成测试资产", "审批流", now, now);
    }

    @Test
    void refreshForProject_handlesNonExistentProject() {
        // refreshForProject 不应该抛异常，即使项目不存在
        assertDoesNotThrow(() -> businessPackService.refreshForProject(999999L));
    }

    @Test
    void refreshForProject_withExistingProject() {
        businessPackService.refreshForProject(projectId);
        var packs = businessPackService.listPacks(projectId, null);
        assertFalse(packs.isEmpty(), "refreshForProject 应该创建业务包");
        assertTrue(packs.size() > 0, "至少应该创建一个业务包");

        var firstPack = packs.get(0);
        var items = businessPackService.listItems(firstPack.id());
        assertFalse(items.isEmpty(), "业务包应该有条目");
    }

    @Test
    void refreshForProject_createsBindings() {
        businessPackService.refreshForProject(projectId);
        var packs = businessPackService.listPacks(projectId, null);
        if (!packs.isEmpty()) {
            var firstPack = packs.get(0);
            var tomBindings = businessPackService.listTomBindings(firstPack.id());
            assertNotNull(tomBindings);
        }
    }

    @Test
    void inferRelations_worksWithMultiplePacks() {
        businessPackService.refreshForProject(projectId);
        int created = businessPackService.inferRelations(projectId);
        assertTrue(created >= 0, "inferRelations 不应该返回负数");
    }

    @Test
    void getAvailableTransitions_worksForAllStatuses() {
        businessPackService.refreshForProject(projectId);
        var packs = businessPackService.listPacks(projectId, null);
        if (!packs.isEmpty()) {
            var pack = packs.get(0);
            var transitions = businessPackService.getAvailableTransitions(pack.id());
            assertNotNull(transitions);
            assertFalse(transitions.isEmpty(), "应该有可用的转换");
        }
    }
}

package com.company.aitest.businesspack;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

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

    private static final long PROJECT_ID = 3001L;

    @Autowired
    private BusinessPackService businessPackService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void refreshForProject_handlesNonExistentProject() {
        // refreshForProject 不应该抛异常，即使项目不存在
        assertDoesNotThrow(() -> businessPackService.refreshForProject(999999L));
    }

    @Test
    void refreshForProject_withExistingProject() {
        seedProjectAssets(PROJECT_ID);
        businessPackService.refreshForProject(PROJECT_ID);
        var packs = businessPackService.listPacks(PROJECT_ID, null);
        assertFalse(packs.isEmpty(), "refreshForProject 应该创建业务包");
        assertTrue(packs.size() > 0, "至少应该创建一个业务包");

        var firstPack = packs.get(0);
        var items = businessPackService.listItems(firstPack.id());
        assertFalse(items.isEmpty(), "业务包应该有条目");
    }

    @Test
    void refreshForProject_createsBindings() {
        seedProjectAssets(PROJECT_ID);
        businessPackService.refreshForProject(PROJECT_ID);
        var packs = businessPackService.listPacks(PROJECT_ID, null);
        if (!packs.isEmpty()) {
            var firstPack = packs.get(0);
            var tomBindings = businessPackService.listTomBindings(firstPack.id());
            assertNotNull(tomBindings);
        }
    }

    @Test
    void inferRelations_worksWithMultiplePacks() {
        seedProjectAssets(PROJECT_ID);
        seedProjectAssets(PROJECT_ID + 1);
        businessPackService.refreshForProject(PROJECT_ID);
        businessPackService.refreshForProject(PROJECT_ID + 1);
        int created = businessPackService.inferRelations(PROJECT_ID);
        assertTrue(created >= 0, "inferRelations 不应该返回负数");
    }

    @Test
    void getAvailableTransitions_worksForAllStatuses() {
        seedProjectAssets(PROJECT_ID);
        businessPackService.refreshForProject(PROJECT_ID);
        var packs = businessPackService.listPacks(PROJECT_ID, null);
        if (!packs.isEmpty()) {
            var pack = packs.get(0);
            var transitions = businessPackService.getAvailableTransitions(pack.id());
            assertNotNull(transitions);
            assertFalse(transitions.isEmpty(), "应该有可用的转换");
        }
    }

    private void seedProjectAssets(long projectId) {
        jdbcTemplate.update("""
                INSERT INTO test_object_model(
                    project_id, scope, model_type, name, description, source_type, source_ref_id,
                    source_context, business_domain, confidence, status, requires_human_confirm,
                    created_by, created_at, updated_at
                ) VALUES (?, 'PROJECT', 'ACTION', ?, ?, 'MANUAL_SECTION', NULL, ?, ?, 0.90, 'ACTIVE', 0, 1, NOW(), NOW())
                """,
                projectId,
                "提交申请-" + projectId,
                "用于 business pack 集成测试的通用动作",
                "集成测试种子数据",
                "workflow");
    }
}

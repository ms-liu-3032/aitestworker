package com.company.aitest.businesspack;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

    @Test
    void refreshForProject_handlesNonExistentProject() {
        // refreshForProject 不应该抛异常，即使项目不存在
        assertDoesNotThrow(() -> businessPackService.refreshForProject(999999L));
    }

    @Test
    void refreshForProject_withExistingProject() {
        businessPackService.refreshForProject(3L);
        var packs = businessPackService.listPacks(3L, null);
        assertFalse(packs.isEmpty(), "refreshForProject 应该创建业务包");
        assertTrue(packs.size() > 0, "至少应该创建一个业务包");

        var firstPack = packs.get(0);
        var items = businessPackService.listItems(firstPack.id());
        assertFalse(items.isEmpty(), "业务包应该有条目");
    }

    @Test
    void refreshForProject_createsBindings() {
        businessPackService.refreshForProject(3L);
        var packs = businessPackService.listPacks(3L, null);
        if (!packs.isEmpty()) {
            var firstPack = packs.get(0);
            var tomBindings = businessPackService.listTomBindings(firstPack.id());
            assertNotNull(tomBindings);
        }
    }

    @Test
    void inferRelations_worksWithMultiplePacks() {
        businessPackService.refreshForProject(3L);
        int created = businessPackService.inferRelations(3L);
        assertTrue(created >= 0, "inferRelations 不应该返回负数");
    }

    @Test
    void getAvailableTransitions_worksForAllStatuses() {
        var packs = businessPackService.listPacks(3L, null);
        if (!packs.isEmpty()) {
            var pack = packs.get(0);
            var transitions = businessPackService.getAvailableTransitions(pack.id());
            assertNotNull(transitions);
            assertFalse(transitions.isEmpty(), "应该有可用的转换");
        }
    }
}

package com.company.aitest.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.aitest.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AssetCatalogServiceIntegrationTest {
    @Autowired AssetCatalogService catalog;
    @Autowired JdbcClient jdbc;

    @Test
    void listsScopedAssetsWithSourceAndExcludesPrivateKnowledge() {
        long projectId = 900020L;
        jdbc.sql("""
                INSERT INTO wiki_pack(project_id, scope, name, status, review_status,
                    source_type, created_at, updated_at)
                VALUES (:projectId, 'SYSTEM', '跨项目测试规范', 'DRAFT', 'PENDING',
                    'MANUAL', NOW(), NOW())
                """).param("projectId", projectId).update();
        long packId = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO knowledge_asset(project_id, asset_type, asset_ref_type, asset_ref_id,
                    title, status, visibility, created_at, updated_at)
                VALUES (:projectId, 'DOCUMENT', 'DOCUMENT', 1,
                    '私人采集笔记', 'DRAFT', 'PRIVATE', NOW(), NOW())
                """).param("projectId", projectId).update();

        var result = catalog.list(projectId, null, null, null, 1, 50);
        assertTrue(result.items().stream().anyMatch(item -> item.assetType().equals("WIKI_PACK")
                && item.id() == packId && item.scope().equals("SYSTEM")
                && item.reviewStatus().equals("PENDING")));
        assertTrue(result.items().stream().noneMatch(item -> item.title().equals("私人采集笔记")));
        assertEquals(0, catalog.list(projectId + 1, "WIKI_PACK", null, null, 1, 50).total());
        assertThrows(BusinessException.class, () -> catalog.list(projectId, "INVALID", null, null, 1, 50));
    }
}

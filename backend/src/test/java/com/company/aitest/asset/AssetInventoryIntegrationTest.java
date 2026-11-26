package com.company.aitest.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AssetInventoryIntegrationTest {
    @Autowired
    private AssetInventoryService inventory;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void inventoriesMigratedSchemaAndFiltersByProject() {
        long projectId = 900018L;
        jdbc.sql("""
                INSERT INTO test_object_model
                    (project_id, model_type, name, source_type, status, scope, created_by)
                VALUES (:projectId, 'MODULE', '资产盘点测试模块', 'MANUAL_IMPORT', 'ACTIVE', 'PROJECT', 1)
                """).param("projectId", projectId).update();

        var projectRows = inventory.list(projectId);
        assertEquals(1L, projectRows.stream()
                .filter(row -> "TOM_NODE".equals(row.assetType()))
                .mapToLong(AssetInventoryService.InventoryRow::count).sum());
        assertTrue(inventory.list(null).stream()
                .anyMatch(row -> "TOM_NODE".equals(row.assetType()) && row.count() >= 1));
    }
}

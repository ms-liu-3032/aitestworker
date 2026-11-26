package com.company.aitest.review;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AssetReviewInboxQueryIntegrationTest {
    @Autowired
    private AssetReviewInboxQuery inbox;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void includesWikiPackAndEntryWithoutMixingOtherProjects() {
        long projectId = 900019L;
        jdbc.sql("""
                INSERT INTO wiki_pack(project_id, scope, name, status, review_status, created_at, updated_at)
                VALUES (:projectId, 'PROJECT', '审核队列测试知识包', 'DRAFT', 'PENDING', NOW(), NOW())
                """).param("projectId", projectId).update();
        long packId = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO wiki_entry(pack_id, entry_type, title, content, review_status, effective_status,
                                       created_at, updated_at)
                VALUES (:packId, 'RULE', '预约规则', '预约需要先审批。', 'PENDING', 'INACTIVE', NOW(), NOW())
                """).param("packId", packId).update();

        var rows = inbox.list(null, projectId, null);
        assertTrue(rows.stream().anyMatch(row -> "wiki_pack".equals(row.get("candidateType"))
                && packId == ((Number) row.get("id")).longValue()));
        assertTrue(rows.stream().anyMatch(row -> "wiki_entry".equals(row.get("candidateType"))
                && packId == ((Number) row.get("packId")).longValue()));
        assertTrue(inbox.list(null, projectId + 1, null).stream().noneMatch(row -> packId ==
                ((Number) row.getOrDefault("packId", row.get("id"))).longValue()));
    }
}

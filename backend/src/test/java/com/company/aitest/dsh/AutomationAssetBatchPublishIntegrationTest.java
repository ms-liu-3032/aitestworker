package com.company.aitest.dsh;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class AutomationAssetBatchPublishIntegrationTest {
    @Autowired AutomationAssetService assets;
    @Autowired JdbcClient jdbc;

    @Test
    void batchIsAtomicVersionBoundAndIdempotent() {
        CurrentUser admin = new CurrentUser(1L, "batch-publish-test", "ADMIN");
        jdbc.sql("INSERT INTO project(project_name, status, created_at, updated_at) VALUES ('批量发布测试', 'ACTIVE', NOW(), NOW())")
                .update();
        long projectId = lastId();
        try {
            long first = asset(projectId, "BATCH-PUBLISH-1", "VERIFIED");
            long second = asset(projectId, "BATCH-PUBLISH-2", "VERIFIED");
            long invalid = asset(projectId, "BATCH-PUBLISH-3", "DRAFT");

            assertThrows(BusinessException.class, () -> assets.publishReusableBatch(projectId,
                    new AutomationAssetService.BatchPublicationInput(List.of(
                            new AutomationAssetService.PublicationTarget(first, 1))),
                    new CurrentUser(2L, "non-owner", "USER")));
            assertEquals(0, publicationCount(projectId));

            assertThrows(BusinessException.class, () -> assets.publishReusableBatch(projectId,
                    new AutomationAssetService.BatchPublicationInput(List.of(
                            new AutomationAssetService.PublicationTarget(first, 1),
                            new AutomationAssetService.PublicationTarget(invalid, 1))), admin));
            assertEquals(0, publicationCount(projectId), "任一条不合格时整批回滚");

            var targets = new AutomationAssetService.BatchPublicationInput(List.of(
                    new AutomationAssetService.PublicationTarget(second, 1),
                    new AutomationAssetService.PublicationTarget(first, 1)));
            var published = assets.publishReusableBatch(projectId, targets, admin);
            assertEquals(2, published.size());
            assertEquals(2, publicationCount(projectId));
            assertEquals(published, assets.publishReusableBatch(projectId, targets, admin));
            assertEquals(2, publicationCount(projectId), "重试不新增发布记录");

            assertThrows(BusinessException.class, () -> assets.publishReusableBatch(projectId,
                    new AutomationAssetService.BatchPublicationInput(List.of(
                            new AutomationAssetService.PublicationTarget(first, 2))), admin));
            assertEquals(2, publicationCount(projectId));
        } finally {
            jdbc.sql("DELETE FROM automation_publication WHERE project_id = :projectId")
                    .param("projectId", projectId).update();
            jdbc.sql("DELETE v FROM automation_asset_version v JOIN automation_asset a ON a.id = v.asset_id WHERE a.project_id = :projectId")
                    .param("projectId", projectId).update();
            jdbc.sql("DELETE FROM automation_asset WHERE project_id = :projectId")
                    .param("projectId", projectId).update();
            jdbc.sql("DELETE FROM test_case_asset WHERE project_id = :projectId")
                    .param("projectId", projectId).update();
            jdbc.sql("DELETE FROM project WHERE id = :projectId")
                    .param("projectId", projectId).update();
        }
    }

    private long asset(long projectId, String caseNo, String status) {
        jdbc.sql("""
                INSERT INTO test_case_asset(project_id, source_task_id, source_draft_id, case_no, case_title,
                    created_at, updated_at) VALUES (:projectId, 1, 1, :caseNo, :caseNo, NOW(), NOW())
                """).param("projectId", projectId).param("caseNo", caseNo).update();
        long caseId = lastId();
        jdbc.sql("""
                INSERT INTO automation_asset(project_id, case_kind, case_id, asset_type, source_work_session_id,
                    source_case_version, scope, status, current_version, heal_count, request_key, created_by,
                    created_at, updated_at)
                VALUES (:projectId, 'FORMAL', :caseId, 'UI', 1, 1, 'PROJECT', :status, 1, 0, :caseNo, 1,
                    NOW(), NOW())
                """).param("projectId", projectId).param("caseId", caseId).param("status", status)
                .param("caseNo", caseNo).update();
        long assetId = lastId();
        jdbc.sql("""
                INSERT INTO automation_asset_version(asset_id, version_no, mapping_json, mapping_hash, change_reason,
                    revision_kind, status, request_key, created_by, created_at, review_status)
                VALUES (:assetId, 1, '{}', REPEAT('0', 64), '批量发布测试', 'INITIAL', 'VERIFIED', :caseNo,
                    1, NOW(), 'APPROVED')
                """).param("assetId", assetId).param("caseNo", caseNo).update();
        return assetId;
    }

    private int publicationCount(long projectId) {
        return jdbc.sql("SELECT COUNT(*) FROM automation_publication WHERE project_id = :projectId")
                .param("projectId", projectId).query(Integer.class).single();
    }

    private long lastId() { return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single(); }
}

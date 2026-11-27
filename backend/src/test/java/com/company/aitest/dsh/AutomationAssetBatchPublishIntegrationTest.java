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
    @Autowired DshWorkSessionService sessions;
    @Autowired com.company.aitest.review.AssetReviewInboxQuery reviewInbox;
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

            assertEquals(3, assets.list(projectId, admin).size());
            assertEquals(3, assets.page(projectId, 1, 2, admin).total());
            assertEquals(1, assets.page(projectId, 2, 2, admin).items().size());
            assertEquals(first, assets.get(projectId, first, admin).id());
            long firstCase = jdbc.sql("SELECT case_id FROM automation_asset WHERE id=:id")
                    .param("id", first).query(Long.class).single();
            jdbc.sql("UPDATE test_case_asset SET case_status='DRAFT' WHERE id=:id")
                    .param("id", firstCase).update();
            assertThrows(BusinessException.class, () -> assets.publishReusableVersionBound(projectId, first, 1, admin));
            jdbc.sql("UPDATE test_case_asset SET case_status='SUBMITTED' WHERE id=:id")
                    .param("id", firstCase).update();

            var targets = new AutomationAssetService.BatchPublicationInput(List.of(
                    new AutomationAssetService.PublicationTarget(second, 1),
                    new AutomationAssetService.PublicationTarget(first, 1)));
            var published = assets.publishReusableBatch(projectId, targets, admin);
            assertEquals(2, published.size());
            assertEquals(2, publicationCount(projectId));
            assertEquals(published, assets.publishReusableBatch(projectId, targets, admin));
            assertEquals(2, publicationCount(projectId), "重试不新增发布记录");
            assertEquals(2, assets.page(projectId, 1, 50, "REUSABLE", "UI", admin).total());

            assertThrows(BusinessException.class, () -> assets.publishReusableBatch(projectId,
                    new AutomationAssetService.BatchPublicationInput(List.of(
                            new AutomationAssetService.PublicationTarget(first, 2))), admin));
            assertEquals(2, publicationCount(projectId));

            // Publishing a newer verified version retires the old recommendation without erasing history.
            jdbc.sql("""
                    INSERT INTO automation_asset_version(asset_id, version_no, mapping_json, mapping_hash,
                        change_reason, revision_kind, status, request_key, created_by, created_at, review_status)
                    VALUES (:assetId, 2, '{}', REPEAT('1', 64), '页面改版', 'MANUAL', 'VERIFIED',
                        'lifecycle-v2', 1, NOW(), 'APPROVED')
                    """).param("assetId", first).update();
            jdbc.sql("UPDATE automation_asset SET current_version=2 WHERE id=:assetId")
                    .param("assetId", first).update();
            long replacement = assets.publishReusableVersionBound(projectId, first, 2, admin);
            assertEquals("SUPERSEDED", jdbc.sql("SELECT status FROM automation_publication WHERE id=:id")
                    .param("id", published.get(0).publicationId()).query(String.class).single());
            assertThrows(BusinessException.class,
                    () -> assets.reusableVersion(published.get(0).publicationId(), admin));
            var request = assets.requestSystem(projectId, first, 2, admin);
            assertEquals("PENDING_REVIEW", request.status());
            assertEquals(true, reviewInbox.list("automation", projectId, "PENDING").stream()
                    .anyMatch(item -> "automation_system".equals(item.get("candidateType"))
                            && request.id().equals(item.get("id"))));
            assertThrows(BusinessException.class, () -> assets.reviewSystem(projectId, first,
                    request.id(), true, null, new CurrentUser(2L, "member", "USER")));
            assertEquals("ACTIVE", assets.reviewSystem(projectId, first, request.id(), true, null, admin).status());
            assertEquals(1, assets.page(projectId, 1, 50, "SYSTEM", "UI", admin).total());
            assertEquals("WITHDRAWN", assets.withdrawPublication(projectId, first, request.id(),
                    new AutomationAssetService.DispositionInput("系统版下线"), admin).status());
            assertEquals("WITHDRAWN", assets.withdrawPublication(projectId, first, replacement,
                    new AutomationAssetService.DispositionInput("复用版下线"), admin).status());
            assertEquals("ARCHIVED", assets.archive(projectId, first,
                    new AutomationAssetService.DispositionInput("资产不再使用"), admin).status());
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

    @Test
    void publishedMappingIsDiscoveredAndAdoptedAsTargetDraft() {
        CurrentUser admin = new CurrentUser(1L, "cross-project-test", "ADMIN");
        jdbc.sql("INSERT INTO project(project_name, status, created_at, updated_at) VALUES ('复用源', 'ACTIVE', NOW(), NOW())").update();
        long sourceProject = lastId();
        jdbc.sql("INSERT INTO project(project_name, status, created_at, updated_at) VALUES ('复用目标', 'ACTIVE', NOW(), NOW())").update();
        long targetProject = lastId();
        long sourceAsset = 0;
        long adoptedAsset = 0;
        long targetCase = 0;
        long workSession = 0;
        long task = 0;
        try {
            sourceAsset = asset(sourceProject, "BATCH-PUBLISH-SOURCE", "VERIFIED");
            jdbc.sql("""
                    UPDATE automation_asset_version SET mapping_json=:mapping WHERE asset_id=:id
                    """).param("id", sourceAsset).param("mapping", """
                    {"caseNo":"BATCH-PUBLISH-SOURCE","page":"/regression",
                     "steps":[{"type":"click","locator":{"by":"text","value":"查看"}}],
                     "assertions":[{"by":"text","value":"详情"}]}
                    """).update();
            long publication = assets.publishReusableVersionBound(sourceProject, sourceAsset, 1, admin);
            jdbc.sql("""
                    INSERT INTO test_case_asset(project_id, source_task_id, source_draft_id, case_no, case_title,
                        case_status, created_at, updated_at)
                    VALUES (:projectId, 1, 1, 'TARGET-CASE', '复用目标用例', 'SUBMITTED', NOW(), NOW())
                    """).param("projectId", targetProject).update();
            targetCase = lastId();
            var session = sessions.start(targetProject, "验证跨项目复用", "DIRECT", admin);
            workSession = session.id();
            task = session.taskId();
            var candidates = assets.reuseCandidates(targetProject, "BATCH-PUBLISH-SOURCE", "UI", 0, 50, admin);
            assertEquals(publication, candidates.get(0).get("publicationId"));
            var adopted = assets.adoptReusable(targetProject, publication,
                    new AutomationAssetService.AdoptionInput(workSession, "adopt-cross-project", "FORMAL", targetCase), admin);
            adoptedAsset = adopted.id();
            assertEquals("DRAFT", adopted.status());
            assertEquals("TARGET-CASE", assets.version(targetProject, adopted.id(), 1, admin)
                    .mapping().path("caseNo").asText());
            assertEquals(1, assets.publications(sourceProject, sourceAsset, admin).get(0).adoptionCount());
        } finally {
            if (adoptedAsset > 0) {
                jdbc.sql("DELETE FROM automation_adoption WHERE target_asset_id=:id").param("id", adoptedAsset).update();
                jdbc.sql("DELETE FROM automation_asset_version WHERE asset_id=:id").param("id", adoptedAsset).update();
                jdbc.sql("DELETE FROM automation_asset WHERE id=:id").param("id", adoptedAsset).update();
            }
            if (workSession > 0) jdbc.sql("DELETE FROM dsh_work_session WHERE id=:id").param("id", workSession).update();
            if (task > 0) jdbc.sql("DELETE FROM generation_task WHERE id=:id").param("id", task).update();
            if (targetCase > 0) jdbc.sql("DELETE FROM test_case_asset WHERE id=:id").param("id", targetCase).update();
            jdbc.sql("DELETE FROM automation_publication WHERE project_id=:id").param("id", sourceProject).update();
            if (sourceAsset > 0) {
                jdbc.sql("DELETE FROM automation_asset_version WHERE asset_id=:id").param("id", sourceAsset).update();
                jdbc.sql("DELETE FROM automation_asset WHERE id=:id").param("id", sourceAsset).update();
            }
            jdbc.sql("DELETE FROM test_case_asset WHERE project_id=:id").param("id", sourceProject).update();
            jdbc.sql("DELETE FROM project WHERE id IN (:source, :target)").param("source", sourceProject)
                    .param("target", targetProject).update();
        }
    }

    private long asset(long projectId, String caseNo, String status) {
        jdbc.sql("""
                INSERT INTO test_case_asset(project_id, source_task_id, source_draft_id, case_no, case_title,
                    case_status, created_at, updated_at) VALUES (:projectId, 1, 1, :caseNo, :caseNo,
                    'SUBMITTED', NOW(), NOW())
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

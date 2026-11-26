package com.company.aitest.dsh;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.project.ProjectAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutomationAssetService {
    private static final Set<String> ACTIONS = Set.of("click", "fill", "press", "select", "check", "uncheck", "waitFor", "scroll", "drag");
    private static final Set<String> LOCATORS = Set.of("testId", "role", "label", "placeholder", "text", "css", "nth");
    private static final Set<String> RESULTS = Set.of("PASS", "LOCATOR_FAILURE", "ASSERTION_FAILURE",
            "ENVIRONMENT_FAILURE", "DATA_FAILURE", "OTHER_FAILURE");
    private static final Set<String> API_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");
    private static final Set<String> API_TARGETS = Set.of("STATUS", "HEADER", "JSON");
    private static final Set<String> API_OPERATORS = Set.of("EQ", "CONTAINS", "EXISTS");
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final ObjectMapper mapper;
    private final TimeProvider clock;
    private final ProjectAccessService access;
    private final DshWorkSessionService sessions;
    private final DshCaptureService captures;

    public AutomationAssetService(JdbcClient jdbc, JdbcTemplate template, ObjectMapper mapper, TimeProvider clock,
            ProjectAccessService access, DshWorkSessionService sessions, DshCaptureService captures) {
        this.jdbc = jdbc;
        this.template = template;
        this.mapper = mapper;
        this.clock = clock;
        this.access = access;
        this.sessions = sessions;
        this.captures = captures;
    }

    public record CreateInput(Long workSessionId, String requestKey, String caseKind, Long caseId,
                              String assetType, JsonNode mapping) {}
    public record HealInput(String requestKey, Long failedRunId, String diagnosis, JsonNode mapping) {}
    public record ManualRevisionInput(String requestKey, Integer expectedVersion, String changeReason, JsonNode mapping) {}
    public record DispositionInput(String reason) {}
    public record ApprovalTarget(Long assetId, Integer versionNo) {}
    public record BatchApprovalInput(List<ApprovalTarget> targets) {}
    public record RunInput(String requestKey, Integer versionNo, String result, Long durationMs,
                           Long evidenceSessionId, Integer failureStepIndex, String failureReason,
                           Long apiEvidenceId, JsonNode logEvidence) {}
    public record ApiEvidenceInput(String requestKey, Integer versionNo, String result, JsonNode evidence) {}
    public record ApiEvidence(Long id, Long assetId, int versionNo, String result, JsonNode evidence) {}
    public record Asset(Long id, Long projectId, String caseKind, Long caseId, String caseNo,
                        String status, int currentVersion, int latestVersion, int healCount,
                        String blockedReason, Long createdBy, int sourceCaseVersion, int caseVersion,
                        boolean caseStale, boolean caseMissing, String reviewStatus, String archiveReason,
                        Long archivedBy, LocalDateTime archivedAt, String assetType) {}
    public record Version(Long assetId, int versionNo, JsonNode mapping, String status,
                          String changeReason, String revisionKind, Long sourceRunId, Long verifiedRunId,
                          String reviewStatus, Long reviewedBy, String reviewReason) {}
    public record Run(Long id, Long assetId, int versionNo, String result, long durationMs,
                      Long evidenceSessionId, Integer failureStepIndex, String failureReason, Long apiEvidenceId, JsonNode logEvidence) {}

    @Transactional
    public Asset create(Long projectId, CreateInput input, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        if (input == null || input.workSessionId() == null) throw new BusinessException("需关联当前 DSH 测试任务");
        requireKey(input.requestKey());
        var session = sessions.get(projectId, input.workSessionId(), user);
        if (!"ACTIVE".equals(session.status())) throw new BusinessException("测试任务已关闭");
        String assetType = assetType(input.assetType());
        String caseNo = caseNo(projectId, input.caseKind(), input.caseId(), user);
        String mapping = canonical(input.mapping(), caseNo, assetType);
        String hash = sha256(mapping);
        List<Long> existingByKey = jdbc.sql("""
                SELECT id FROM automation_asset WHERE project_id = :projectId AND created_by = :userId AND request_key = :requestKey
                """).param("projectId", projectId).param("userId", user.id()).param("requestKey", input.requestKey())
                .query(Long.class).list();
        if (!existingByKey.isEmpty()) {
            Asset existing = get(projectId, existingByKey.get(0), user);
            if (!existing.caseKind().equals(input.caseKind()) || !existing.caseId().equals(input.caseId())
                    || !existing.assetType().equals(assetType)
                    || !hash.equals(versionHash(existing.id(), 1))) throw new BusinessException("幂等键已用于其他自动化资产");
            return existing;
        }
        List<Long> existingCase = jdbc.sql("""
                SELECT id FROM automation_asset WHERE project_id = :projectId AND case_kind = :kind
                  AND case_id = :caseId AND asset_type = :assetType
                """).param("projectId", projectId).param("kind", input.caseKind()).param("caseId", input.caseId())
                .param("assetType", assetType)
                .query(Long.class).list();
        if (!existingCase.isEmpty()) {
            Asset existing = get(projectId, existingCase.get(0), user);
            if ("ARCHIVED".equals(existing.status())) throw new BusinessException("该用例的自动化资产已舍弃，请在 Web 查看历史记录");
            if (!hash.equals(versionHash(existing.id(), 1))) throw new BusinessException("该用例已有自动化资产，请提交版本修订");
            return existing;
        }
        int sourceCaseVersion = caseVersion(projectId, input.caseKind(), input.caseId(), user);
        LocalDateTime now = clock.now();
        template.update("""
                INSERT INTO automation_asset(project_id, case_kind, case_id, asset_type, source_work_session_id, source_case_version, scope,
                    status, current_version, heal_count, request_key, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PROJECT', 'DRAFT', 1, 0, ?, ?, ?, ?)
                """, projectId, input.caseKind(), input.caseId(), assetType, input.workSessionId(), sourceCaseVersion,
                input.requestKey(), user.id(), now, now);
        Long id = lastId();
        template.update("""
                INSERT INTO automation_asset_version(asset_id, version_no, mapping_json, mapping_hash, change_reason,
                    revision_kind, status, request_key, created_by, created_at)
                VALUES (?, 1, ?, ?, '首次执行映射', 'INITIAL', 'DRAFT', ?, ?, ?)
                """, id, mapping, hash, input.requestKey(), user.id(), now);
        return get(projectId, id, user);
    }

    public List<Asset> list(Long projectId, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        int manage = canManageProject(projectId, user) ? 1 : 0;
        return jdbc.sql("""
                SELECT a.*, (SELECT MAX(v.version_no) FROM automation_asset_version v WHERE v.asset_id = a.id) latest_version,
                       (SELECT v.review_status FROM automation_asset_version v WHERE v.asset_id = a.id AND v.version_no = a.current_version) review_status,
                       CASE a.case_kind WHEN 'DRAFT' THEN d.case_no ELSE c.case_no END case_no,
                       CASE a.case_kind WHEN 'DRAFT' THEN d.version_no ELSE c.version END case_version
                FROM automation_asset a
                LEFT JOIN test_case_draft d ON a.case_kind = 'DRAFT' AND d.id = a.case_id
                LEFT JOIN test_case_asset c ON a.case_kind = 'FORMAL' AND c.id = a.case_id
                WHERE a.project_id = :projectId AND (a.case_kind = 'FORMAL' OR a.created_by = :userId OR :manage = 1)
                ORDER BY a.id DESC LIMIT 200
                """).param("projectId", projectId).param("userId", user.id()).param("manage", manage)
                .query(this::mapAsset).list();
    }

    public Asset get(Long projectId, Long assetId, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        int manage = canManageProject(projectId, user) ? 1 : 0;
        return jdbc.sql("""
                SELECT a.*, (SELECT MAX(v.version_no) FROM automation_asset_version v WHERE v.asset_id = a.id) latest_version,
                       (SELECT v.review_status FROM automation_asset_version v WHERE v.asset_id = a.id AND v.version_no = a.current_version) review_status,
                       CASE a.case_kind WHEN 'DRAFT' THEN d.case_no ELSE c.case_no END case_no,
                       CASE a.case_kind WHEN 'DRAFT' THEN d.version_no ELSE c.version END case_version
                FROM automation_asset a
                LEFT JOIN test_case_draft d ON a.case_kind = 'DRAFT' AND d.id = a.case_id
                LEFT JOIN test_case_asset c ON a.case_kind = 'FORMAL' AND c.id = a.case_id
                WHERE a.id = :id AND a.project_id = :projectId
                  AND (a.case_kind = 'FORMAL' OR a.created_by = :userId OR :manage = 1)
                """).param("id", assetId).param("projectId", projectId).param("userId", user.id())
                .param("manage", manage)
                .query(this::mapAsset).optional().orElseThrow(() -> new BusinessException("自动化资产不存在或无权访问"));
    }

    public Version version(Long projectId, Long assetId, int versionNo, CurrentUser user) {
        get(projectId, assetId, user);
        return jdbc.sql("""
                SELECT * FROM automation_asset_version WHERE asset_id = :assetId AND version_no = :versionNo
                """).param("assetId", assetId).param("versionNo", versionNo)
                .query((rs, row) -> new Version(assetId, rs.getInt("version_no"), parse(rs.getString("mapping_json")),
                        rs.getString("status"), rs.getString("change_reason"), rs.getString("revision_kind"),
                        rs.getObject("source_run_id", Long.class), rs.getObject("verified_run_id", Long.class),
                        rs.getString("review_status"), rs.getObject("reviewed_by", Long.class), rs.getString("review_reason")))
                .optional().orElseThrow(() -> new BusinessException("执行映射版本不存在"));
    }

    public List<Run> runs(Long projectId, Long assetId, CurrentUser user) {
        get(projectId, assetId, user);
        return jdbc.sql("SELECT * FROM automation_run WHERE asset_id = :assetId ORDER BY id DESC LIMIT 100")
                .param("assetId", assetId).query(this::mapRun).list();
    }

    @Transactional
    public ApiEvidence recordApiEvidence(Long projectId, Long assetId, ApiEvidenceInput input, CurrentUser user) {
        Asset asset = get(projectId, assetId, user);
        if (!"API".equals(asset.assetType()) || "ARCHIVED".equals(asset.status()))
            throw new BusinessException("只有可用的接口自动化资产可写入接口证据");
        if (input == null || input.versionNo() == null || input.result() == null || !RESULTS.contains(input.result()))
            throw new BusinessException("接口证据版本或运行结果无效");
        requireKey(input.requestKey());
        Version version = version(projectId, assetId, input.versionNo(), user);
        if ("REJECTED".equals(version.status())) throw new BusinessException("已舍弃版本不能写入证据");
        JsonNode evidence = input.evidence();
        if (evidence == null || !evidence.isObject() || !evidence.path("steps").isArray()
                || evidence.path("steps").isEmpty() || !evidence.path("assertions").isArray())
            throw new BusinessException("接口证据须包含步骤与断言结果");
        if ("PASS".equals(input.result())) {
            if (evidence.path("steps").size() != version.mapping().path("steps").size()
                    || evidence.path("assertions").size() != version.mapping().path("assertions").size())
                throw new BusinessException("PASS 证据未覆盖全部接口步骤和断言");
            for (JsonNode check : evidence.path("assertions"))
                if (!check.path("passed").asBoolean(false)) throw new BusinessException("PASS 证据包含失败断言");
        }
        String payload = json(redactEvidence(evidence));
        if (payload.length() > 100_000) throw new BusinessException("接口证据超过 100000 字");
        List<Long> prior = jdbc.sql("SELECT id FROM automation_api_evidence WHERE asset_id = :id AND request_key = :key")
                .param("id", assetId).param("key", input.requestKey()).query(Long.class).list();
        if (!prior.isEmpty()) {
            ApiEvidence existing = apiEvidence(projectId, assetId, prior.get(0), user);
            if (existing.versionNo() != input.versionNo() || !existing.result().equals(input.result())
                    || !json(existing.evidence()).equals(payload)) throw new BusinessException("幂等键已用于其他接口证据");
            return existing;
        }
        template.update("""
                INSERT INTO automation_api_evidence(project_id, asset_id, version_no, request_key,
                    result, evidence_json, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, projectId, assetId, input.versionNo(), input.requestKey(), input.result(), payload,
                user.id(), clock.now());
        return apiEvidence(projectId, assetId, lastId(), user);
    }

    public ApiEvidence apiEvidence(Long projectId, Long assetId, Long evidenceId, CurrentUser user) {
        Asset asset = get(projectId, assetId, user);
        if (!"API".equals(asset.assetType())) throw new BusinessException("该资产不是接口自动化资产");
        return jdbc.sql("""
                SELECT * FROM automation_api_evidence WHERE id = :id AND asset_id = :assetId
                  AND project_id = :projectId
                """).param("id", evidenceId).param("assetId", assetId).param("projectId", projectId)
                .query((rs, row) -> new ApiEvidence(rs.getLong("id"), rs.getLong("asset_id"),
                        rs.getInt("version_no"), rs.getString("result"), parse(rs.getString("evidence_json"))))
                .optional().orElseThrow(() -> new BusinessException("接口证据不存在或无权访问"));
    }

    @Transactional
    public Run recordRun(Long projectId, Long assetId, RunInput input, CurrentUser user) {
        Asset asset = get(projectId, assetId, user);
        if (input == null || input.versionNo() == null
                || input.durationMs() == null || input.durationMs() < 0 || input.durationMs() > 86_400_000
                || input.result() == null || !RESULTS.contains(input.result())) throw new BusinessException("运行结果字段无效");
        if ("UI".equals(asset.assetType()) ? input.evidenceSessionId() == null || input.apiEvidenceId() != null
                : input.apiEvidenceId() == null || input.evidenceSessionId() != null)
            throw new BusinessException("运行证据类型与自动化资产类型不匹配");
        if ("API".equals(asset.assetType()) && "LOCATOR_FAILURE".equals(input.result()))
            throw new BusinessException("接口运行不能使用浏览器定位失败结果");
        requireKey(input.requestKey());
        if (input.failureReason() != null && input.failureReason().length() > 2000)
            throw new BusinessException("失败原因最多 2000 字");
        if (input.failureStepIndex() != null && (input.failureStepIndex() < 0 || input.failureStepIndex() > 100))
            throw new BusinessException("失败步骤编号无效");
        Version evidenceVersion = version(projectId, assetId, input.versionNo(), user);
        JsonNode logEvidence = LogAssertionPolicy.evidence(mapper, evidenceVersion.mapping(), input.result(), input.logEvidence());
        List<Run> existing = jdbc.sql("SELECT * FROM automation_run WHERE asset_id = :id AND request_key = :key")
                .param("id", assetId).param("key", input.requestKey()).query(this::mapRun).list();
        if (!existing.isEmpty()) {
            Run run = existing.get(0);
            if (run.versionNo() != input.versionNo() || !run.result().equals(input.result())
                    || !Objects.equals(run.evidenceSessionId(), input.evidenceSessionId())
                    || !Objects.equals(run.apiEvidenceId(), input.apiEvidenceId())
                    || !Objects.equals(run.logEvidence(), logEvidence))
                throw new BusinessException("幂等键已用于其他运行结果");
            return run;
        }
        if ("UI".equals(asset.assetType())) {
            var capture = captures.run(projectId, input.evidenceSessionId(), user);
            if (!"FINISHED".equals(capture.status()) || captures.evidence(projectId, input.evidenceSessionId(), user).isEmpty())
                throw new BusinessException("运行必须关联已上传原始证据的完成采集会话");
        } else {
            ApiEvidence evidence = apiEvidence(projectId, assetId, input.apiEvidenceId(), user);
            if (evidence.versionNo() != input.versionNo() || !evidence.result().equals(input.result()))
                throw new BusinessException("接口证据与运行版本或结果不一致");
        }
        // Lock the asset before deciding which version can become current.
        jdbc.sql("SELECT id FROM automation_asset WHERE id = :id FOR UPDATE").param("id", assetId).query(Long.class).single();
        asset = get(projectId, assetId, user);
        if ("ARCHIVED".equals(asset.status())) throw new BusinessException("资产已舍弃，不能执行");
        if (asset.caseMissing()) throw new BusinessException("关联用例已不存在；请舍弃旧资产并为现有用例建立映射");
        if ("BLOCKED".equals(asset.status())) throw new BusinessException("资产已阻断，须在 Web 端人工解除");
        if (asset.caseStale() && input.versionNo().equals(asset.currentVersion()))
            throw new BusinessException("归属用例已有新版本，请人工修订执行映射后验证");
        Version version = version(projectId, assetId, input.versionNo(), user);
        if ("REJECTED".equals(version.status())) throw new BusinessException("已驳回的映射版本不能执行");
        if (input.versionNo() == asset.currentVersion() && "VERIFIED".equals(version.status())
                && !"APPROVED".equals(version.reviewStatus()))
            throw new BusinessException("当前执行映射尚未在 Web 审核通过，不能日常复跑");
        if (asset.caseStale() && "HEAL".equals(version.revisionKind()))
            throw new BusinessException("用例已更新，定位自愈候选不能替代完整映射修订");
        if (input.versionNo() != asset.currentVersion()
                && (input.versionNo() <= asset.currentVersion() || !"DRAFT".equals(version.status())))
            throw new BusinessException("只能执行当前版本或待验证的自愈版本");
        LocalDateTime now = clock.now();
        template.update("""
                INSERT INTO automation_run(asset_id, version_no, result, duration_ms, evidence_session_id, api_evidence_id,
                    failure_step_index, failure_reason, request_key, created_by, created_at, log_evidence_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, assetId, input.versionNo(), input.result(), input.durationMs(), input.evidenceSessionId(), input.apiEvidenceId(),
                input.failureStepIndex(), input.failureReason(), input.requestKey(), user.id(), now, logEvidence == null ? null : json(logEvidence));
        Long runId = lastId();
        if ("PASS".equals(input.result())) {
            template.update("""
                    UPDATE automation_asset_version SET status = 'VERIFIED',
                        verified_run_id = COALESCE(verified_run_id, ?), verified_at = COALESCE(verified_at, ?)
                    WHERE asset_id = ? AND version_no = ?
                    """, runId, now, assetId, input.versionNo());
            int healed = "HEAL".equals(version.revisionKind()) && "DRAFT".equals(version.status()) ? 1 : 0;
            if (healed == 1) template.update("""
                    UPDATE automation_heal_record SET status = 'VERIFIED', resolved_at = ?
                    WHERE asset_id = ? AND to_version = ? AND status = 'PENDING'
                    """, now, assetId, input.versionNo());
            int count = asset.healCount() + healed;
            String status = "VERIFIED";
            template.update("""
                    UPDATE automation_asset SET current_version = ?, heal_count = ?, status = ?,
                        blocked_reason = ?, source_case_version = ?, updated_at = ? WHERE id = ?
                    """, input.versionNo(), count, status, null,
                    asset.caseVersion(), now, assetId);
            // A proven locator-only revision of an already approved personal asset can keep running.
            // Formal assets still require a reviewer for every newly verified version.
            if (healed == 1 && "DRAFT".equals(asset.caseKind())
                    && "APPROVED".equals(version(projectId, assetId, asset.currentVersion(), user).reviewStatus()))
                template.update("""
                        UPDATE automation_asset_version SET review_status = 'APPROVED', reviewed_by = ?, reviewed_at = ?
                        WHERE asset_id = ? AND version_no = ?
                        """, user.id(), now, assetId, input.versionNo());
        } else {
            if (input.versionNo() != asset.currentVersion() && "DRAFT".equals(version.status())
                    && !"ENVIRONMENT_FAILURE".equals(input.result())) {
                template.update("UPDATE automation_asset_version SET status = 'REJECTED' WHERE asset_id = ? AND version_no = ?",
                        assetId, input.versionNo());
                if ("HEAL".equals(version.revisionKind())) template.update("""
                            UPDATE automation_heal_record SET status = 'REJECTED', resolved_at = ?
                            WHERE asset_id = ? AND to_version = ? AND status = 'PENDING'
                            """, now, assetId, input.versionNo());
            }
            if ("UI".equals(asset.assetType()) && "LOCATOR_FAILURE".equals(input.result())
                    && input.versionNo() == asset.currentVersion()) template.update(
                    "UPDATE automation_asset SET status = 'DEGRADED', updated_at = ? WHERE id = ?", now, assetId);
        }
        return jdbc.sql("SELECT * FROM automation_run WHERE id = ?").param(runId).query(this::mapRun).single();
    }

    @Transactional
    public Version proposeHeal(Long projectId, Long assetId, HealInput input, CurrentUser user) {
        Asset asset = get(projectId, assetId, user);
        if (!"UI".equals(asset.assetType())) throw new BusinessException("接口自动化不支持定位器自愈；请修订并重新审核接口映射");
        if (input == null || input.failedRunId() == null) throw new BusinessException("须关联失败运行");
        requireKey(input.requestKey());
        if (input.diagnosis() == null || input.diagnosis().strip().length() < 10 || input.diagnosis().length() > 2000)
            throw new BusinessException("自愈诊断须为 10 至 2000 字");
        List<Integer> duplicate = jdbc.sql("""
                SELECT version_no FROM automation_asset_version WHERE asset_id = :id AND request_key = :key
                """).param("id", assetId).param("key", input.requestKey()).query(Integer.class).list();
        if (!duplicate.isEmpty()) {
            Version prior = version(projectId, assetId, duplicate.get(0), user);
            Run sourceRun = jdbc.sql("SELECT * FROM automation_run WHERE id = :id AND asset_id = :assetId")
                    .param("id", input.failedRunId()).param("assetId", assetId).query(this::mapRun)
                    .optional().orElseThrow(() -> new BusinessException("失败运行不存在"));
            JsonNode base = version(projectId, assetId, sourceRun.versionNo(), user).mapping();
            JsonNode submitted = parse(canonical(input.mapping(), asset.caseNo()));
            if (!sameExceptLocators(base, submitted)
                    || !sha256(canonical(retainLocatorBranches(base, submitted), asset.caseNo()))
                    .equals(versionHash(assetId, prior.versionNo()))
                    || !input.failedRunId().equals(prior.sourceRunId()))
                throw new BusinessException("幂等键已用于其他自愈版本");
            return prior;
        }
        jdbc.sql("SELECT id FROM automation_asset WHERE id = :id FOR UPDATE").param("id", assetId).query(Long.class).single();
        asset = get(projectId, assetId, user);
        if ("ARCHIVED".equals(asset.status())) throw new BusinessException("资产已舍弃，不能自愈");
        if (asset.caseMissing()) throw new BusinessException("关联用例已不存在，不能自愈；请舍弃旧资产");
        if (!"DEGRADED".equals(asset.status())) throw new BusinessException("只有定位失败后的降级资产可自愈；BLOCKED 需人工解除");
        if (asset.caseStale()) throw new BusinessException("用例已有新版本，须由项目管理者修订完整映射");
        Run failed = jdbc.sql("SELECT * FROM automation_run WHERE id = :id AND asset_id = :assetId")
                .param("id", input.failedRunId()).param("assetId", assetId).query(this::mapRun)
                .optional().orElseThrow(() -> new BusinessException("失败运行不存在"));
        if (!"LOCATOR_FAILURE".equals(failed.result()) || failed.versionNo() != asset.currentVersion())
            throw new BusinessException("仅当前版本的定位失败可自愈；断言、环境和数据失败不得修改预期");
        Version current = version(projectId, assetId, asset.currentVersion(), user);
        String proposed = canonical(input.mapping(), asset.caseNo());
        JsonNode next = parse(proposed);
        if (!sameExceptLocators(current.mapping(), next))
            throw new BusinessException("自动自愈只允许修改定位器，不允许修改步骤动作、测试数据或断言预期");
        next = retainLocatorBranches(current.mapping(), next);
        if (current.mapping().equals(next)) throw new BusinessException("自愈后定位器没有有效变化");
        String revised = canonical(next, asset.caseNo());
        List<Integer> pending = jdbc.sql("""
                SELECT version_no FROM automation_asset_version WHERE asset_id = :id AND status = 'DRAFT' AND source_run_id IS NOT NULL
                """).param("id", assetId).query(Integer.class).list();
        if (!pending.isEmpty()) throw new BusinessException("已有待验证自愈版本，请先执行或驳回");
        int nextVersion = asset.latestVersion() + 1;
        LocalDateTime now = clock.now();
        template.update("""
                INSERT INTO automation_asset_version(asset_id, version_no, mapping_json, mapping_hash, change_reason,
                    revision_kind, status, source_run_id, request_key, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, 'HEAL', 'DRAFT', ?, ?, ?, ?)
                """, assetId, nextVersion, revised, sha256(revised), input.diagnosis().strip(),
                failed.id(), input.requestKey(), user.id(), now);
        template.update("""
                INSERT INTO automation_heal_record(asset_id, from_version, to_version, failure_run_id,
                    diagnosis, diff_json, status, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, assetId, asset.currentVersion(), nextVersion, failed.id(), input.diagnosis().strip(),
                locatorDiff(current.mapping(), next), user.id(), now);
        return version(projectId, assetId, nextVersion, user);
    }

    @Transactional
    public Version proposeManualVersion(Long projectId, Long assetId, ManualRevisionInput input, CurrentUser user) {
        Asset asset = get(projectId, assetId, user);
        if ("DRAFT".equals(asset.caseKind()) && asset.createdBy().equals(user.id()))
            access.ensureCanAccess(projectId, user);
        else access.ensureCanManageProject(projectId, user);
        if ("ARCHIVED".equals(asset.status())) throw new BusinessException("资产已舍弃，不能修订");
        if (asset.caseMissing()) throw new BusinessException("关联用例已不存在，不能复验；请舍弃旧资产");
        if (input == null || input.expectedVersion() == null || input.mapping() == null
                || input.changeReason() == null || input.changeReason().strip().length() < 10
                || input.changeReason().length() > 512) throw new BusinessException("请说明人工修订依据和当前版本");
        requireKey(input.requestKey());
        String mapping = canonical(input.mapping(), asset.caseNo(), asset.assetType());
        List<Integer> duplicate = jdbc.sql("""
                SELECT version_no FROM automation_asset_version WHERE asset_id = :id AND request_key = :key
                """).param("id", assetId).param("key", input.requestKey()).query(Integer.class).list();
        if (!duplicate.isEmpty()) {
            if (!sha256(mapping).equals(versionHash(assetId, duplicate.get(0))))
                throw new BusinessException("幂等键已用于其他版本内容");
            return version(projectId, assetId, duplicate.get(0), user);
        }
        jdbc.sql("SELECT id FROM automation_asset WHERE id = :id FOR UPDATE").param("id", assetId).query(Long.class).single();
        asset = get(projectId, assetId, user);
        if (asset.currentVersion() != input.expectedVersion() || "BLOCKED".equals(asset.status()))
            throw new BusinessException("资产版本已变化或处于人工阻断状态");
        List<Integer> pending = jdbc.sql("""
                SELECT version_no FROM automation_asset_version WHERE asset_id = :id AND status = 'DRAFT' AND version_no > 1
                """).param("id", assetId).query(Integer.class).list();
        if (!pending.isEmpty()) throw new BusinessException("已有待验证版本，请先执行或驳回");
        int next = asset.latestVersion() + 1;
        template.update("""
                INSERT INTO automation_asset_version(asset_id, version_no, mapping_json, mapping_hash, change_reason,
                    revision_kind, status, request_key, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, 'MANUAL', 'DRAFT', ?, ?, ?)
                """, assetId, next, mapping, sha256(mapping), input.changeReason().strip(),
                input.requestKey(), user.id(), clock.now());
        return version(projectId, assetId, next, user);
    }

    @Transactional
    public Version approveVersion(Long projectId, Long assetId, int versionNo, CurrentUser user) {
        ensureReviewer(projectId, get(projectId, assetId, user), user);
        jdbc.sql("SELECT id FROM automation_asset WHERE id = :id AND project_id = :projectId FOR UPDATE")
                .param("id", assetId).param("projectId", projectId).query(Long.class).optional()
                .orElseThrow(() -> new BusinessException("自动化资产不存在"));
        Asset asset = get(projectId, assetId, user);
        if ("ARCHIVED".equals(asset.status()) || "BLOCKED".equals(asset.status()) || asset.caseStale())
            throw new BusinessException(asset.caseMissing() ? "关联用例已不存在，不能审核通过"
                    : "资产已舍弃、阻断或归属用例已更新，不能审核通过");
        if (versionNo != asset.currentVersion() || !"VERIFIED".equals(asset.status()))
            throw new BusinessException("只能审核当前已跑通的执行映射");
        Version selected = version(projectId, assetId, versionNo, user);
        if (!"VERIFIED".equals(selected.status())) throw new BusinessException("映射版本尚未跑通");
        if ("REJECTED".equals(selected.reviewStatus())) throw new BusinessException("已舍弃的版本不能审核");
        if (!"APPROVED".equals(selected.reviewStatus())) template.update("""
                UPDATE automation_asset_version SET review_status = 'APPROVED', reviewed_by = ?, reviewed_at = ?
                WHERE asset_id = ? AND version_no = ?
                """, user.id(), clock.now(), assetId, versionNo);
        return version(projectId, assetId, versionNo, user);
    }

    @Transactional
    public List<Version> approveVersions(Long projectId, BatchApprovalInput input, CurrentUser user) {
        if (input == null || input.targets() == null || input.targets().isEmpty() || input.targets().size() > 100)
            throw new BusinessException("每次请选择 1-100 个待审核资产");
        Set<Long> seen = new java.util.HashSet<>();
        List<Version> approved = new ArrayList<>();
        for (ApprovalTarget target : input.targets()) {
            if (target == null || target.assetId() == null || target.assetId() <= 0
                    || target.versionNo() == null || target.versionNo() <= 0 || !seen.add(target.assetId()))
                throw new BusinessException("批量审核目标无效或重复");
            approved.add(approveVersion(projectId, target.assetId(), target.versionNo(), user));
        }
        return approved;
    }

    @Transactional
    public Version discardCandidate(Long projectId, Long assetId, int versionNo,
                                    DispositionInput input, CurrentUser user) {
        ensureReviewer(projectId, get(projectId, assetId, user), user);
        String reason = dispositionReason(input);
        jdbc.sql("SELECT id FROM automation_asset WHERE id = :id AND project_id = :projectId FOR UPDATE")
                .param("id", assetId).param("projectId", projectId).query(Long.class).optional()
                .orElseThrow(() -> new BusinessException("自动化资产不存在"));
        Asset asset = get(projectId, assetId, user);
        if ("ARCHIVED".equals(asset.status())) throw new BusinessException("资产已舍弃");
        Version selected = version(projectId, assetId, versionNo, user);
        if (versionNo <= asset.currentVersion() || !"DRAFT".equals(selected.status()))
            throw new BusinessException("只能舍弃尚未生效的待验证候选版；当前版本请舍弃整个资产");
        template.update("""
                UPDATE automation_asset_version SET status = 'REJECTED', review_status = 'REJECTED',
                    reviewed_by = ?, reviewed_at = ?, review_reason = ? WHERE asset_id = ? AND version_no = ?
                """, user.id(), clock.now(), reason, assetId, versionNo);
        template.update("""
                UPDATE automation_heal_record SET status = 'REJECTED', resolved_at = ?
                WHERE asset_id = ? AND to_version = ? AND status = 'PENDING'
                """, clock.now(), assetId, versionNo);
        return version(projectId, assetId, versionNo, user);
    }

    @Transactional
    public Asset archive(Long projectId, Long assetId, DispositionInput input, CurrentUser user) {
        String reason = dispositionReason(input);
        Asset asset = get(projectId, assetId, user);
        if (!("DRAFT".equals(asset.caseKind()) && asset.createdBy().equals(user.id())))
            access.ensureCanManageProject(projectId, user);
        jdbc.sql("SELECT id FROM automation_asset WHERE id = :id AND project_id = :projectId FOR UPDATE")
                .param("id", assetId).param("projectId", projectId).query(Long.class).optional()
                .orElseThrow(() -> new BusinessException("自动化资产不存在"));
        asset = get(projectId, assetId, user);
        if ("ARCHIVED".equals(asset.status())) return asset;
        int publications = jdbc.sql("SELECT COUNT(*) FROM automation_publication WHERE asset_id = :id AND status = 'ACTIVE'")
                .param("id", assetId).query(Integer.class).single();
        if (publications > 0) throw new BusinessException("资产已发布跨项目复用版，须先处理发布关系，不能舍弃");
        template.update("""
                UPDATE automation_asset SET status = 'ARCHIVED', archived_by = ?, archived_at = ?,
                    archive_reason = ?, updated_at = ? WHERE id = ?
                """, user.id(), clock.now(), reason, clock.now(), assetId);
        return get(projectId, assetId, user);
    }

    @Transactional
    public Asset archivePersonal(Long projectId, Long assetId, DispositionInput input, CurrentUser user) {
        requirePersonalAsset(projectId, assetId, user);
        return archive(projectId, assetId, input, user);
    }

    @Transactional
    public Version discardPersonalCandidate(Long projectId, Long assetId, int versionNo,
                                            DispositionInput input, CurrentUser user) {
        requirePersonalAsset(projectId, assetId, user);
        return discardCandidate(projectId, assetId, versionNo, input, user);
    }

    private void requirePersonalAsset(Long projectId, Long assetId, CurrentUser user) {
        Asset asset = get(projectId, assetId, user);
        if (!"DRAFT".equals(asset.caseKind()) || !asset.createdBy().equals(user.id()))
            throw new BusinessException("DSH 只能舍弃本人个人草稿的自动化映射；正式资产请在 Web 管理");
    }

    private static String dispositionReason(DispositionInput input) {
        String reason = input == null ? null : input.reason();
        if (reason == null || reason.strip().length() < 2 || reason.length() > 512)
            throw new BusinessException("请填写 2-512 字的舍弃原因");
        return reason.strip();
    }

    private void ensureReviewer(Long projectId, Asset asset, CurrentUser user) {
        if ("DRAFT".equals(asset.caseKind()) && asset.createdBy().equals(user.id()))
            access.ensureCanAccess(projectId, user);
        else access.ensureCanManageProject(projectId, user);
    }

    private boolean canManageProject(Long projectId, CurrentUser user) {
        if (access.isPlatformAdmin(user)) return true;
        return jdbc.sql("""
                SELECT COUNT(*) FROM project_member
                WHERE project_id = :projectId AND user_id = :userId AND project_role = 'OWNER'
                """).param("projectId", projectId).param("userId", user.id()).query(Integer.class).single() > 0;
    }

    @Transactional
    public Asset unblock(Long projectId, Long assetId, CurrentUser user) {
        access.ensureCanManageProject(projectId, user);
        Asset asset = get(projectId, assetId, user);
        if (!"BLOCKED".equals(asset.status())) throw new BusinessException("资产未被阻断");
        template.update("UPDATE automation_asset SET status = 'VERIFIED', blocked_reason = NULL, updated_at = ? WHERE id = ?",
                clock.now(), assetId);
        return get(projectId, assetId, user);
    }

    @Transactional
    public Long publishReusable(Long projectId, Long assetId, CurrentUser user) {
        access.ensureCanManageProject(projectId, user);
        Asset asset = get(projectId, assetId, user);
        if (!"VERIFIED".equals(asset.status())) throw new BusinessException("只有已验证且未阻断的版本可发布复用");
        if (!"APPROVED".equals(version(projectId, assetId, asset.currentVersion(), user).reviewStatus()))
            throw new BusinessException("执行映射须先在 Web 审核通过");
        if (asset.caseStale()) throw new BusinessException("用例已有新版本，须重新验证映射后发布");
        if (!"FORMAL".equals(asset.caseKind())) throw new BusinessException("个人草稿映射不能跨项目发布，请先提交正式用例");
        List<Long> existing = jdbc.sql("""
                SELECT id FROM automation_publication WHERE asset_id = :id AND version_no = :version AND scope = 'REUSABLE'
                """).param("id", assetId).param("version", asset.currentVersion()).query(Long.class).list();
        if (!existing.isEmpty()) return existing.get(0);
        template.update("""
                INSERT INTO automation_publication(asset_id, project_id, version_no, scope, status, published_by, published_at)
                VALUES (?, ?, ?, 'REUSABLE', 'ACTIVE', ?, ?)
                """, assetId, projectId, asset.currentVersion(), user.id(), clock.now());
        return lastId();
    }

    public List<Map<String, Object>> reusable(String query, CurrentUser user) {
        if (user == null) throw new BusinessException("请先登录");
        String term = query == null ? "" : query.strip();
        if (term.length() > 100) throw new BusinessException("检索词最多 100 字");
        String pattern = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return jdbc.sql("""
                SELECT p.id publication_id, p.asset_id, p.project_id, p.version_no, a.case_kind, a.case_id, a.asset_type,
                       CASE a.case_kind WHEN 'DRAFT' THEN d.case_no ELSE c.case_no END case_no
                FROM automation_publication p JOIN automation_asset a ON a.id = p.asset_id
                LEFT JOIN test_case_draft d ON a.case_kind = 'DRAFT' AND d.id = a.case_id
                LEFT JOIN test_case_asset c ON a.case_kind = 'FORMAL' AND c.id = a.case_id
                WHERE p.status = 'ACTIVE' AND (d.case_no LIKE :pattern ESCAPE '!' OR c.case_no LIKE :pattern ESCAPE '!')
                ORDER BY p.id DESC LIMIT 20
                """).param("pattern", pattern).query((rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("publicationId", rs.getLong("publication_id"));
                    item.put("assetId", rs.getLong("asset_id"));
                    item.put("sourceProjectId", rs.getLong("project_id"));
                    item.put("versionNo", rs.getInt("version_no"));
                    item.put("assetType", rs.getString("asset_type"));
                    item.put("caseNo", rs.getString("case_no"));
                    return item;
                }).list();
    }

    public Map<String, Object> reusableVersion(Long publicationId, CurrentUser user) {
        if (user == null || publicationId == null || publicationId < 1) throw new BusinessException("复用资产编号无效");
        return jdbc.sql("""
                SELECT p.id, p.project_id, p.asset_id, p.version_no, a.asset_type, v.mapping_json
                FROM automation_publication p JOIN automation_asset_version v
                  ON v.asset_id = p.asset_id AND v.version_no = p.version_no
                JOIN automation_asset a ON a.id = p.asset_id
                WHERE p.id = :id AND p.status = 'ACTIVE' AND v.status = 'VERIFIED'
                """).param("id", publicationId).query((rs, row) -> Map.<String, Object>of(
                        "publicationId", rs.getLong("id"), "sourceProjectId", rs.getLong("project_id"),
                        "assetId", rs.getLong("asset_id"), "frozenVersion", rs.getInt("version_no"),
                        "assetType", rs.getString("asset_type"),
                        "mapping", parse(rs.getString("mapping_json")))).optional()
                .orElseThrow(() -> new BusinessException("复用资产不存在或不可用"));
    }

    private Asset mapAsset(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Integer caseVersion = rs.getObject("case_version", Integer.class);
        boolean caseMissing = caseVersion == null;
        int sourceCaseVersion = rs.getInt("source_case_version");
        return new Asset(rs.getLong("id"), rs.getLong("project_id"), rs.getString("case_kind"),
                rs.getLong("case_id"), rs.getString("case_no"), rs.getString("status"),
                rs.getInt("current_version"), rs.getInt("latest_version"), rs.getInt("heal_count"),
                rs.getString("blocked_reason"), rs.getLong("created_by"), sourceCaseVersion,
                caseMissing ? 0 : caseVersion, caseMissing || sourceCaseVersion != caseVersion, caseMissing,
                rs.getString("review_status"), rs.getString("archive_reason"),
                rs.getObject("archived_by", Long.class), rs.getObject("archived_at", LocalDateTime.class),
                rs.getString("asset_type"));
    }

    private Run mapRun(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Run(rs.getLong("id"), rs.getLong("asset_id"), rs.getInt("version_no"),
                rs.getString("result"), rs.getLong("duration_ms"), rs.getObject("evidence_session_id", Long.class),
                rs.getObject("failure_step_index", Integer.class), rs.getString("failure_reason"),
                rs.getObject("api_evidence_id", Long.class), rs.getString("log_evidence_json") == null ? null : parse(rs.getString("log_evidence_json")));
    }

    private String caseNo(Long projectId, String kind, Long caseId, CurrentUser user) {
        if (caseId == null || caseId < 1) throw new BusinessException("用例编号无效");
        if ("DRAFT".equals(kind)) return jdbc.sql("""
                SELECT case_no FROM test_case_draft WHERE id = :id AND project_id = :projectId
                  AND created_by = :userId AND case_status = 'DRAFT'
                """).param("id", caseId).param("projectId", projectId).param("userId", user.id())
                .query(String.class).optional().orElseThrow(() -> new BusinessException("个人草稿不存在或无权访问"));
        if ("FORMAL".equals(kind)) return jdbc.sql("""
                SELECT case_no FROM test_case_asset WHERE id = :id AND project_id = :projectId
                  AND case_status = 'SUBMITTED' AND deprecated_at IS NULL
                """).param("id", caseId).param("projectId", projectId).query(String.class)
                .optional().orElseThrow(() -> new BusinessException("正式用例不存在或不可用"));
        throw new BusinessException("用例归属类型必须为 DRAFT 或 FORMAL");
    }

    private int caseVersion(Long projectId, String kind, Long caseId, CurrentUser user) {
        if ("DRAFT".equals(kind)) return jdbc.sql("""
                SELECT version_no FROM test_case_draft WHERE id = :id AND project_id = :projectId AND created_by = :userId
                """).param("id", caseId).param("projectId", projectId).param("userId", user.id())
                .query(Integer.class).single();
        return jdbc.sql("SELECT version FROM test_case_asset WHERE id = :id AND project_id = :projectId")
                .param("id", caseId).param("projectId", projectId).query(Integer.class).single();
    }

    private String versionHash(Long assetId, int versionNo) {
        return jdbc.sql("SELECT mapping_hash FROM automation_asset_version WHERE asset_id = ? AND version_no = ?")
                .param(assetId).param(versionNo).query(String.class).single();
    }

    String canonical(JsonNode mapping, String caseNo) { return canonical(mapping, caseNo, "UI"); }

    String canonical(JsonNode mapping, String caseNo, String type) {
        if (mapping == null || !mapping.isObject()) throw new BusinessException("执行映射必须是 JSON 对象");
        if (caseNo == null || caseNo.isBlank()) throw new BusinessException("归属用例缺少编号");
        if (!caseNo.equals(text(mapping, "caseNo"))) throw new BusinessException("执行映射的 caseNo 与归属用例不一致");
        LogAssertionPolicy.validateMapping(mapping, "API".equals(type));
        if ("API".equals(type)) return canonicalApi(mapping);
        String page = text(mapping, "page");
        if (page == null || !page.startsWith("/") || page.startsWith("//") || page.length() > 1024)
            throw new BusinessException("页面须为被测站点内的相对路径");
        JsonNode steps = mapping.path("steps");
        JsonNode assertions = mapping.path("assertions");
        JsonNode uiCoverage = mapping.path("uiCoverage");
        if (!uiCoverage.isMissingNode()) {
            if (!uiCoverage.isObject() || text(uiCoverage, "ruleCode") == null
                    || !text(uiCoverage, "ruleCode").matches("[A-Z][A-Z0-9_]{2,63}")
                    || text(uiCoverage, "fieldKey") == null
                    || !text(uiCoverage, "fieldKey").matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                    || !Set.of("POSITIVE", "NEGATIVE").contains(text(uiCoverage, "role"))
                    || !uiCoverage.path("fieldStepIndex").canConvertToInt()
                    || !uiCoverage.path("submitStepIndex").canConvertToInt())
                throw new BusinessException("UI 必填项覆盖声明无效");
        }
        JsonNode ruleCoverage = mapping.path("ruleCoverage");
        if (!ruleCoverage.isMissingNode() && (!ruleCoverage.isObject()
                || text(ruleCoverage, "ruleCode") == null
                || !text(ruleCoverage, "ruleCode").matches("[A-Z][A-Z0-9_]{2,63}")
                || text(ruleCoverage, "subjectKey") == null
                || !text(ruleCoverage, "subjectKey").matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                || !ruleCoverage.path("assertionIndex").canConvertToInt()))
            throw new BusinessException("UI 规则覆盖声明无效");
        if (!steps.isArray() || steps.isEmpty() || steps.size() > 100 || !assertions.isArray()
                || assertions.isEmpty() || assertions.size() > 30) throw new BusinessException("步骤须为 1-100 条，断言须为 1-30 条");
        for (JsonNode step : steps) {
            if (!step.isObject() || text(step, "type") == null || !ACTIONS.contains(text(step, "type")))
                throw new BusinessException("步骤须使用 type（click/fill/press/select/check/uncheck/waitFor/scroll/drag），不能使用 action");
            validateLocator(step.path("locator"));
            waitMs(step.path("waitBefore"));
            if ("scroll".equals(text(step, "type"))) {
                JsonNode dy = step.path("deltaY");
                JsonNode dx = step.path("deltaX");
                JsonNode repeat = step.path("repeat");
                if (!dy.isIntegralNumber() || !dy.canConvertToInt() || dy.asInt() == 0
                        || dy.asInt() < -2000 || dy.asInt() > 2000
                        || (!dx.isMissingNode() && (!dx.isIntegralNumber() || !dx.canConvertToInt()
                            || dx.asInt() < -2000 || dx.asInt() > 2000))
                        || (!repeat.isMissingNode() && (!repeat.isIntegralNumber() || !repeat.canConvertToInt()
                            || repeat.asInt() < 1 || repeat.asInt() > 10)))
                    throw new BusinessException("滚动步骤需要非零 deltaY（-2000 至 2000），deltaX 须在 ±2000 内，repeat 须为 1–10");
            }
            if ("drag".equals(text(step, "type"))) {
                JsonNode dx = step.path("deltaX");
                JsonNode dy = step.path("deltaY");
                JsonNode moves = step.path("dragSteps");
                if ((!dx.isMissingNode() && (!dx.isIntegralNumber() || !dx.canConvertToInt()
                        || dx.asInt() < -2000 || dx.asInt() > 2000))
                        || (!dy.isMissingNode() && (!dy.isIntegralNumber() || !dy.canConvertToInt()
                            || dy.asInt() < -2000 || dy.asInt() > 2000))
                        || (dx.asInt(0) == 0 && dy.asInt(0) == 0)
                        || (!moves.isMissingNode() && (!moves.isIntegralNumber() || !moves.canConvertToInt()
                            || moves.asInt() < 2 || moves.asInt() > 50)))
                    throw new BusinessException("拖拽步骤需要非零 deltaX 或 deltaY（各在 ±2000 内），dragSteps 须为 2–50");
            }
            if (step.has("waitForResponse")) {
                if (!Set.of("click", "press").contains(text(step, "type")))
                    throw new BusinessException("业务响应等待只支持 click 或 press 步骤");
                JsonNode response = step.path("waitForResponse");
                String path = text(response, "path");
                String method = text(response, "method");
                if (!response.isObject() || path == null || !path.startsWith("/") || path.startsWith("//")
                        || path.contains("?") || path.contains("#") || path.length() > 1024)
                    throw new BusinessException("业务响应路径须为站内绝对路径，不含查询参数");
                if (method != null && !Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method))
                    throw new BusinessException("业务响应请求方法无效");
                if (response.has("status") && (response.path("status").asInt(-1) < 200
                        || response.path("status").asInt() > 299))
                    throw new BusinessException("业务响应状态须为 2xx");
                if (response.has("timeout") && (response.path("timeout").asInt(-1) < 100
                        || response.path("timeout").asInt() > 30000))
                    throw new BusinessException("业务响应超时须为 100-30000 毫秒");
            }
            if ("waitFor".equals(text(step, "type"))) {
                String state = text(step, "state");
                if (state != null && !Set.of("visible", "hidden").contains(state))
                    throw new BusinessException("waitFor 状态只能是 visible 或 hidden");
                if ("hidden".equals(state) && step.has("expected"))
                    throw new BusinessException("等待元素隐藏时不能同时检查文本");
                if (step.has("expected") && (text(step, "expected") == null || text(step, "expected").length() > 1000))
                    throw new BusinessException("waitFor 预期文本无效");
                if (step.has("timeout") && (step.path("timeout").asInt(-1) < 100
                        || step.path("timeout").asInt() > 30000))
                    throw new BusinessException("waitFor 超时须为 100-30000 毫秒");
            }
            if (Set.of("fill", "press", "select").contains(text(step, "type"))) {
                String data = text(step, "data");
                int stepIndex = 0;
                for (JsonNode previous : steps) { if (previous == step) break; stepIndex++; }
                boolean negativeClear = "fill".equals(text(step, "type")) && "NEGATIVE".equals(text(uiCoverage, "role"))
                        && stepIndex == uiCoverage.path("fieldStepIndex").asInt(-1) && "".equals(data);
                if (data == null || !negativeClear && data.isBlank() || data.length() > 1000)
                    throw new BusinessException("输入步骤须使用 data，不能使用 value，且数据不能为空或过长");
                if (data.matches("1[3-9]\\d{9}")) throw new BusinessException("真实格式手机号须改为运行时受控测试数据参数");
            }
        }
        for (JsonNode assertion : assertions) {
            if (!assertion.isObject()) throw new BusinessException("断言格式无效");
            JsonNode locator = assertion.has("locator") ? assertion.path("locator") : assertion;
            validateLocator(locator);
            if (assertion.has("expect")) throw new BusinessException("断言文本须使用 expected，不能使用 expect");
            if (assertion.has("expected") && (text(assertion, "expected") == null || text(assertion, "expected").length() > 1000))
                throw new BusinessException("断言预期无效");
            if (assertion.has("match") && !"contains".equals(text(assertion, "match"))
                    && !"equals".equals(text(assertion, "match")))
                throw new BusinessException("断言文本匹配方式只能是 contains 或 equals");
            waitMs(assertion.path("waitBefore"));
            if (assertion.has("timeout") && (assertion.path("timeout").asInt(-1) < 0 || assertion.path("timeout").asInt() > 30000))
                throw new BusinessException("断言超时须为 0-30000 毫秒");
            if (assertion.has("state") && !Set.of("visible", "hidden").contains(text(assertion, "state")))
                throw new BusinessException("断言状态只能是 visible 或 hidden");
            if ("hidden".equals(text(assertion, "state")) && assertion.has("expected"))
                throw new BusinessException("隐藏断言不能同时检查文本");
        }
        if (!uiCoverage.isMissingNode()) {
            int fieldIndex = uiCoverage.path("fieldStepIndex").asInt(-1);
            int submitIndex = uiCoverage.path("submitStepIndex").asInt(-1);
            if (fieldIndex < 0 || fieldIndex >= steps.size() || submitIndex <= fieldIndex || submitIndex >= steps.size()
                    || !"fill".equals(text(steps.get(fieldIndex), "type"))
                    || !"click".equals(text(steps.get(submitIndex), "type")))
                throw new BusinessException("必填项覆盖映射缺少清空/输入字段与提交动作");
            String data = text(steps.get(fieldIndex), "data");
            if ("NEGATIVE".equals(text(uiCoverage, "role"))) {
                int errorIndex = uiCoverage.path("errorAssertionIndex").asInt(-1);
                int noSuccessIndex = uiCoverage.path("noSuccessAssertionIndex").asInt(-1);
                if (!"".equals(data) || errorIndex < 0 || errorIndex >= assertions.size()
                        || noSuccessIndex < 0 || noSuccessIndex >= assertions.size()
                        || !"hidden".equals(text(assertions.get(noSuccessIndex), "state"))
                        || "hidden".equals(text(assertions.get(errorIndex), "state")))
                    throw new BusinessException("单项留空映射须包含错误提示与无成功结果断言");
            } else if (data == null || data.isBlank()) throw new BusinessException("必填项正向映射须提供有效输入");
        }
        if (!ruleCoverage.isMissingNode()) {
            int index = ruleCoverage.path("assertionIndex").asInt(-1);
            if (index < 0 || index >= assertions.size() || "hidden".equals(text(assertions.get(index), "state")))
                throw new BusinessException("规则覆盖映射须引用可观察断言");
        }
        if (mapping.has("assetVersion") || mapping.has("healCount") || mapping.has("verified") || mapping.has("healedAt"))
            throw new BusinessException("版本、验证状态和自愈次数由 AITestWork 管理，不得由模型填写");
        String json = json(mapping);
        if (json.length() > 100000) throw new BusinessException("执行映射最多 100000 字");
        return json;
    }

    private String canonicalApi(JsonNode mapping) {
        if (mapping.has("page")) throw new BusinessException("接口映射使用 steps[*].path，不能使用浏览器 page");
        JsonNode steps = mapping.path("steps");
        JsonNode assertions = mapping.path("assertions");
        if (!steps.isArray() || steps.isEmpty() || steps.size() > 30 || !assertions.isArray()
                || assertions.isEmpty() || assertions.size() > 100)
            throw new BusinessException("接口步骤须为 1-30 条，断言须为 1-100 条");
        for (JsonNode step : steps) {
            String method = text(step, "method");
            String path = text(step, "path");
            if (!step.isObject() || method == null || !API_METHODS.contains(method) || path == null
                    || !path.startsWith("/") || path.startsWith("//") || path.contains("://")
                    || path.contains("#") || path.matches(".*\\s+.*") || path.length() > 1024)
                throw new BusinessException("接口步骤须包含合法的 method 和站内相对 path");
            if (path.contains("${secret:")) throw new BusinessException("接口路径不能引用密钥；认证信息请放请求头");
            int queryStart = path.indexOf('?');
            if (queryStart >= 0) for (String part : path.substring(queryStart + 1).split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2 && pair[0].matches("(?i).*(password|token|secret|api[_-]?key|authorization|cookie).*")
                        && !pair[1].matches("\\$\\{[A-Za-z][A-Za-z0-9_]*\\}"))
                    throw new BusinessException("敏感查询参数不能保存固定值；请使用运行时变量或认证请求头");
            }
            int timeout = step.path("timeoutMs").asInt(10_000);
            int retries = step.path("retryCount").asInt(0);
            if (timeout < 100 || timeout > 30_000 || retries < 0 || retries > 2
                    || (retries > 0 && !"GET".equals(method)))
                throw new BusinessException("接口超时须为 100-30000 毫秒；仅 GET 可重试 0-2 次");
            if (("GET".equals(method) || "HEAD".equals(method)) && step.has("body"))
                throw new BusinessException("GET/HEAD 接口步骤不能包含请求体");
            JsonNode headers = step.path("headers");
            if (!headers.isMissingNode()) {
                if (!headers.isObject() || headers.size() > 30) throw new BusinessException("接口请求头格式无效或超过 30 项");
                var fields = headers.fields();
                while (fields.hasNext()) {
                    var header = fields.next();
                    if (!header.getKey().matches("[A-Za-z0-9-]{1,64}")) throw new BusinessException("请求头名称无效");
                    if (Set.of("host", "content-length", "connection", "transfer-encoding").contains(header.getKey().toLowerCase()))
                        throw new BusinessException("接口映射不能设置传输层请求头");
                    JsonNode value = header.getValue();
                    if (value.isObject() && value.size() == 1 && text(value, "secretRef") != null
                            && text(value, "secretRef").matches("[A-Z][A-Z0-9_]{0,63}")) continue;
                    if (!value.isTextual() || value.asText().length() > 1000 || value.asText().contains("\n")
                            || value.asText().contains("\r")) throw new BusinessException("请求头只能是短文本或 secretRef");
                    if (Set.of("authorization", "cookie", "x-api-key").contains(header.getKey().toLowerCase())
                            && !value.asText().matches("(?i)(Bearer )?\\$\\{(?:secret:)?[A-Za-z][A-Za-z0-9_]*\\}"))
                        throw new BusinessException("认证请求头只能使用变量或 secretRef，不得存入明文凭据");
                }
            }
            JsonNode extract = step.path("extract");
            if (!extract.isMissingNode()) {
                if (!extract.isObject() || extract.size() > 20) throw new BusinessException("提取变量格式无效或超过 20 项");
                var fields = extract.fields();
                while (fields.hasNext()) {
                    var item = fields.next();
                    if (!item.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                            || !item.getValue().isTextual() || !validJsonPath(item.getValue().asText()))
                        throw new BusinessException("提取变量须使用简单 JSON 路径，如 $.data.id");
                }
            }
            rejectLiteralSecrets(step.path("body"), "");
        }
        for (JsonNode assertion : assertions) {
            String target = text(assertion, "target");
            String operator = text(assertion, "operator");
            int index = assertion.path("stepIndex").asInt(-1);
            if (!assertion.isObject() || target == null || !API_TARGETS.contains(target)
                    || operator == null || !API_OPERATORS.contains(operator)
                    || index < 0 || index >= steps.size())
                throw new BusinessException("接口断言目标、比较方式或步骤序号无效");
            String path = text(assertion, "path");
            if ("JSON".equals(target) && !validJsonPath(path)) throw new BusinessException("JSON 断言路径无效");
            if ("HEADER".equals(target) && (path == null || !path.matches("[A-Za-z0-9-]{1,64}")))
                throw new BusinessException("响应头断言名称无效");
            if (!"EXISTS".equals(operator) && !assertion.has("expected"))
                throw new BusinessException("接口比较断言必须提供 expected");
            if ("STATUS".equals(target) && !"EQ".equals(operator))
                throw new BusinessException("HTTP 状态只支持 EQ 比较");
            if ("CONTAINS".equals(operator) && !assertion.path("expected").isValueNode())
                throw new BusinessException("包含断言的 expected 须为标量");
            if ("STATUS".equals(target) && "EQ".equals(operator)
                    && (!assertion.path("expected").isIntegralNumber()
                    || assertion.path("expected").asInt() < 100 || assertion.path("expected").asInt() > 599))
                throw new BusinessException("HTTP 状态断言须为 100-599 整数");
        }
        if (mapping.has("assetVersion") || mapping.has("healCount") || mapping.has("verified") || mapping.has("healedAt"))
            throw new BusinessException("版本、验证状态和自愈次数由 AITestWork 管理");
        String json = json(mapping);
        if (json.length() > 100_000) throw new BusinessException("接口映射最多 100000 字");
        return json;
    }

    private static boolean validJsonPath(String path) {
        return path != null && path.length() <= 256
                && path.matches("\\$(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[[0-9]{1,4}\\])*");
    }

    private void rejectLiteralSecrets(JsonNode node, String key) {
        if (node.isMissingNode() || node.isNull()) return;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                rejectLiteralSecrets(field.getValue(), field.getKey());
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) rejectLiteralSecrets(item, key);
        } else if (node.isTextual()) {
            String value = node.asText();
            if (value.length() > 10_000 || value.matches("(?i).*Bearer\\s+[A-Za-z0-9._-]{8,}.*")
                    || (key.matches("(?i).*(password|token|secret|api.?key|cookie).*")
                    && !value.matches("\\$\\{secret:[A-Z][A-Z0-9_]{0,63}\\}")))
                throw new BusinessException("接口映射不得保存明文密钥，敏感字段使用 ${secret:NAME}");
        }
    }

    private static String assetType(String value) {
        if (value == null || value.isBlank()) return "UI";
        if (!"UI".equals(value) && !"API".equals(value)) throw new BusinessException("自动化资产类型须为 UI 或 API");
        return value;
    }

    private JsonNode redactEvidence(JsonNode input) {
        if (input.isObject()) {
            ObjectNode output = mapper.createObjectNode();
            input.fields().forEachRemaining(field -> output.set(field.getKey(),
                    field.getKey().matches("(?i).*(authorization|cookie|password|token|secret|api.?key).*")
                            ? mapper.getNodeFactory().textNode("[REDACTED]") : redactEvidence(field.getValue())));
            return output;
        }
        if (input.isArray()) {
            ArrayNode output = mapper.createArrayNode();
            input.forEach(item -> output.add(redactEvidence(item)));
            return output;
        }
        if (input.isTextual()) {
            String value = input.asText().replaceAll("(?i)Bearer\\s+[A-Za-z0-9._-]+", "Bearer [REDACTED]")
                    .replaceAll("(?i)(password|token|secret|api.?key)=([^&\\s]+)", "$1=[REDACTED]");
            return mapper.getNodeFactory().textNode(value.substring(0, Math.min(4000, value.length())));
        }
        return input;
    }

    private void validateLocator(JsonNode locator) { validateLocator(locator, true); }

    private void validateLocator(JsonNode locator, boolean allowFallback) {
        if (!locator.isObject() || text(locator, "by") == null || !LOCATORS.contains(text(locator, "by")))
            throw new BusinessException("定位器须包含 by（testId/role/label/placeholder/text/css/nth）");
        String by = text(locator, "by");
        JsonNode within = locator.path("within");
        if (!within.isMissingNode()) {
            if (!allowFallback || within.has("within") || within.has("fallback"))
                throw new BusinessException("定位器作用域只能嵌套一层，不能包含备用链");
            validateLocator(within, false);
        }
        if ("nth".equals(by)) {
            String base = text(locator, "base");
            int index = locator.path("index").asInt(Integer.MIN_VALUE);
            if (base == null || base.isBlank() || base.length() > 500 || index < -100 || index > 100)
                throw new BusinessException("nth 定位器须包含 base 和有效 index");
        } else {
            String value = text(locator, "value");
            if (value == null || value.isBlank() || value.length() > 500) throw new BusinessException("定位值不能为空或过长");
        }
        JsonNode fallback = locator.path("fallback");
        if (!fallback.isMissingNode()) {
            if (!allowFallback || !fallback.isArray() || fallback.isEmpty() || fallback.size() > 4)
                throw new BusinessException("定位备用链最多 4 个且不能嵌套");
            for (JsonNode candidate : fallback) validateLocator(candidate, false);
        }
    }

    private void waitMs(JsonNode value) {
        if (!value.isMissingNode() && (!value.canConvertToInt() || value.asInt() < 0 || value.asInt() > 30000))
            throw new BusinessException("waitBefore 须为 0-30000 毫秒");
    }

    boolean sameExceptLocators(JsonNode before, JsonNode after) {
        JsonNode a = before.deepCopy();
        JsonNode b = after.deepCopy();
        stripLocators(a);
        stripLocators(b);
        return a.equals(b) && !before.equals(after);
    }

    JsonNode retainLocatorBranches(JsonNode before, JsonNode after) {
        ObjectNode merged = after.deepCopy();
        for (int i = 0; i < before.path("steps").size(); i++) {
            ObjectNode step = (ObjectNode) merged.path("steps").get(i);
            ObjectNode proposed = ((ObjectNode) step.path("locator")).deepCopy();
            JsonNode previous = before.path("steps").get(i).path("locator");
            List<JsonNode> candidates = new ArrayList<>();
            candidates.add(proposed);
            proposed.path("fallback").forEach(candidates::add);
            candidates.add(previous);
            previous.path("fallback").forEach(candidates::add);
            List<JsonNode> unique = new ArrayList<>();
            for (JsonNode candidate : candidates) {
                ObjectNode clean = ((ObjectNode) candidate).deepCopy();
                clean.remove("fallback");
                if (!unique.contains(clean)) unique.add(clean);
            }
            ObjectNode primary = ((ObjectNode) unique.get(0)).deepCopy();
            ArrayNode fallback = mapper.createArrayNode();
            for (int j = 1; j < unique.size() && j <= 4; j++) fallback.add(unique.get(j));
            if (!fallback.isEmpty()) primary.set("fallback", fallback);
            step.set("locator", primary);
        }
        return merged;
    }

    private void stripLocators(JsonNode mapping) {
        for (JsonNode step : mapping.path("steps")) if (step instanceof ObjectNode object) object.remove("locator");
    }

    private String locatorDiff(JsonNode oldMapping, JsonNode newMapping) {
        List<Map<String, Object>> changed = new ArrayList<>();
        for (int i = 0; i < oldMapping.path("steps").size(); i++) {
            JsonNode from = oldMapping.path("steps").get(i).path("locator");
            JsonNode to = newMapping.path("steps").get(i).path("locator");
            if (!from.equals(to)) changed.add(Map.of("stepIndex", i, "from", from, "to", to));
        }
        for (int i = 0; i < oldMapping.path("assertions").size(); i++) {
            JsonNode from = oldMapping.path("assertions").get(i);
            JsonNode to = newMapping.path("assertions").get(i);
            if (!from.equals(to)) changed.add(Map.of("assertionIndex", i, "from", from, "to", to));
        }
        return json(changed);
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isTextual() ? value.asText() : null;
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) throw new BusinessException("幂等键须为 1-128 字");
    }

    private JsonNode parse(String json) {
        try { return mapper.readTree(json); }
        catch (JsonProcessingException e) { throw new BusinessException("执行映射 JSON 无效"); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new BusinessException("执行映射 JSON 无效"); }
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private Long lastId() { return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single(); }
}

package com.company.aitest.dsh;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.project.ProjectAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DshDraftService {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final ProjectAccessService access;
    private final ObjectMapper mapper;
    private final DshWorkSessionService workSessions;
    private final DshCaptureService captures;

    public DshDraftService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider,
                           ProjectAccessService access, ObjectMapper mapper, DshWorkSessionService workSessions,
                           DshCaptureService captures) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        this.access = access;
        this.mapper = mapper;
        this.workSessions = workSessions;
        this.captures = captures;
    }

    @Transactional
    public SavedDraft save(Long projectId, DraftInput input, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        if (input == null || input.workSessionId() == null || input.workSessionId() < 1
                || blank(input.idempotencyKey()) || input.idempotencyKey().length() > 128
                || blank(input.requirementText()) || blank(input.caseTitle())
                || blank(input.steps()) || blank(input.expectedResult())) {
            throw new BusinessException("本轮任务、幂等键、需求、标题、步骤和预期结果不能为空");
        }
        if (input.requirementText().length() > 100_000 || input.caseTitle().length() > 255
                || length(input.moduleName()) > 255 || length(input.precondition()) > 30_000
                || input.steps().length() > 30_000 || input.expectedResult().length() > 30_000
                || (input.sourceRefs() != null && (input.sourceRefs().size() > 50 || input.sourceRefs().stream()
                .anyMatch(ref -> ref == null || ref.length() > 512)))) {
            throw new BusinessException("用例字段或来源引用过长");
        }
        String requestSourceJson;
        if (input.captureSessionId() != null) {
            var capture = captures.run(projectId, input.captureSessionId(), user);
            if (!"FINISHED".equals(capture.status()) || !input.workSessionId().equals(capture.workSessionId())) {
                throw new BusinessException("采集证据必须已完成并属于本轮测试任务");
            }
        }
        try {
            var sources = new LinkedHashMap<String, Object>();
            sources.put("assetRefs", input.sourceRefs() == null ? List.of() : input.sourceRefs());
            if (input.captureSessionId() != null) sources.put("traceSessionId", input.captureSessionId());
            requestSourceJson = mapper.writeValueAsString(sources);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("来源引用格式错误");
        }
        String hash = sha256(String.join("\u001f", String.valueOf(input.workSessionId()), input.requirementText(), input.caseTitle(), input.steps(),
                input.expectedResult(), requestSourceJson));
        var existing = jdbc.sql("""
                SELECT request_hash, draft_id FROM dsh_draft_request
                WHERE user_id = :userId AND project_id = :projectId AND request_key = :requestKey
                """)
                .param("userId", user.id()).param("projectId", projectId)
                .param("requestKey", input.idempotencyKey())
                .query((rs, rowNum) -> new SavedDraft(rs.getLong("draft_id"), rs.getString("request_hash")))
                .optional();
        if (existing.isPresent()) {
            if (!hash.equals(existing.get().requestHash())) throw new BusinessException("幂等键已用于其他草稿");
            return existing.get();
        }
        List<ReferenceSnapshot> sourceSnapshots = resolveSourceRefs(projectId, user, input.sourceRefs());
        String sourceJson;
        try {
            var sources = new LinkedHashMap<String, Object>();
            sources.put("assetRefs", input.sourceRefs() == null ? List.of() : input.sourceRefs());
            sources.put("assetRefSnapshots", sourceSnapshots);
            if (input.captureSessionId() != null) sources.put("traceSessionId", input.captureSessionId());
            sourceJson = mapper.writeValueAsString(sources);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("来源引用格式错误");
        }
        var now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO dsh_draft_request(user_id, project_id, work_session_id, request_key, request_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, user.id(), projectId, input.workSessionId(), input.idempotencyKey(), hash, now);
        if (!input.requirementText().equals(workSessions.get(projectId, input.workSessionId(), user).requirementText())) {
            throw new BusinessException("用例需求内容与本轮测试任务不一致");
        }
        Long taskId = workSessions.taskIdForDraft(projectId, input.workSessionId(), user);
        var methodology = workSessions.requireDraftMatchesDesign(projectId, input.workSessionId(), input.caseTitle(),
                input.moduleName(), input.precondition(), input.steps(), input.expectedResult(), user);
        String scenarioType = methodology.scenarioType();
        String designMethod = methodology.designMethod();
        jdbcTemplate.update("""
                INSERT INTO test_case_draft(task_id, project_id, case_no, case_title, module_name,
                    precondition, steps, expected_result, priority, case_type, scenario_type, design_method,
                    source_refs_json, quality_status, version_no, asset_status, case_scope, case_status,
                    created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'FUNCTIONAL', ?, ?, ?, 'PENDING_REVIEW', 1,
                    'DRAFT', 'PERSONAL', 'DRAFT', ?, ?, ?)
                """, taskId, projectId, null, input.caseTitle().trim(), input.moduleName(),
                input.precondition(), input.steps(), input.expectedResult(), normalizePriority(input.priority()),
                scenarioType, designMethod, sourceJson, user.id(), now, now);
        Long draftId = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbcTemplate.update("UPDATE test_case_draft SET case_no = ? WHERE id = ?", "DSH-" + draftId, draftId);
        jdbcTemplate.update("""
                UPDATE dsh_draft_request SET draft_id = ?
                WHERE user_id = ? AND project_id = ? AND request_key = ?
                """, draftId, user.id(), projectId, input.idempotencyKey());
        return new SavedDraft(draftId, hash);
    }

    @Transactional
    public RevisedDraft revise(Long projectId, Long draftId, DraftRevisionInput input, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        if (input == null || input.workSessionId() == null || input.expectedVersion() == null
                || blank(input.idempotencyKey()) || input.idempotencyKey().length() > 128
                || blank(input.caseTitle()) || blank(input.steps()) || blank(input.expectedResult()))
            throw new BusinessException("任务、草稿版本、幂等键、标题、步骤和预期不能为空");
        if (input.caseTitle().length() > 255 || length(input.moduleName()) > 255
                || length(input.precondition()) > 30000 || input.steps().length() > 30000
                || input.expectedResult().length() > 30000) throw new BusinessException("草稿内容过长");
        var session = workSessions.get(projectId, input.workSessionId(), user);
        DraftState current = jdbc.sql("""
                SELECT id, task_id, case_status, version_no, case_title, module_name, precondition,
                    steps, expected_result, priority
                FROM test_case_draft WHERE id = :id AND project_id = :projectId AND created_by = :userId FOR UPDATE
                """).param("id", draftId).param("projectId", projectId).param("userId", user.id())
                .query((rs, row) -> new DraftState(rs.getLong("id"), rs.getLong("task_id"),
                        rs.getString("case_status"), rs.getInt("version_no"), rs.getString("case_title"),
                        rs.getString("module_name"), rs.getString("precondition"), rs.getString("steps"),
                        rs.getString("expected_result"), rs.getString("priority")))
                .optional().orElseThrow(() -> new BusinessException("个人草稿不存在或无权编辑"));
        if (!"DRAFT".equals(current.caseStatus()) || !current.taskId().equals(session.taskId()))
            throw new BusinessException("只能修改本轮任务中未提交的个人草稿");
        String hash = sha256(String.join("\u001f", input.caseTitle(), safe(input.moduleName()),
                safe(input.precondition()), input.steps(), input.expectedResult(), normalizePriority(input.priority())));
        List<RevisedDraft> previous = jdbc.sql("""
                SELECT draft_id, version_no, content_hash FROM dsh_draft_revision
                WHERE draft_id = :id AND request_key = :key
                """).param("id", draftId).param("key", input.idempotencyKey())
                .query((rs, row) -> new RevisedDraft(rs.getLong("draft_id"), rs.getInt("version_no"),
                        rs.getString("content_hash"))).list();
        if (!previous.isEmpty()) {
            if (!hash.equals(previous.get(0).contentHash())) throw new BusinessException("幂等键已用于其他草稿修订");
            return previous.get(0);
        }
        if (current.versionNo() != input.expectedVersion()) throw new BusinessException("草稿已有新版本，请先读取后再修改");
        workSessions.requireDraftMatchesDesign(projectId, input.workSessionId(), input.caseTitle(),
                input.moduleName(), input.precondition(), input.steps(), input.expectedResult(), user);
        LocalDateTime now = timeProvider.now();
        var oldContent = new LinkedHashMap<String, Object>();
        oldContent.put("caseTitle", current.caseTitle());
        oldContent.put("moduleName", current.moduleName());
        oldContent.put("precondition", current.precondition());
        oldContent.put("steps", current.steps());
        oldContent.put("expectedResult", current.expectedResult());
        oldContent.put("priority", current.priority());
        String oldJson = json(oldContent);
        jdbcTemplate.update("""
                INSERT IGNORE INTO dsh_draft_revision(draft_id, version_no, content_json, content_hash,
                    request_key, changed_by, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, draftId, current.versionNo(), oldJson, sha256(oldJson),
                "INITIAL:" + current.versionNo(), user.id(), now);
        int next = current.versionNo() + 1;
        int changed = jdbcTemplate.update("""
                UPDATE test_case_draft SET case_title = ?, module_name = ?, precondition = ?, steps = ?,
                    expected_result = ?, priority = ?, quality_status = 'PENDING_REVIEW',
                    version_no = ?, updated_at = ?
                WHERE id = ? AND project_id = ? AND created_by = ? AND version_no = ? AND case_status = 'DRAFT'
                """, input.caseTitle().strip(), input.moduleName(), input.precondition(), input.steps(),
                input.expectedResult(), normalizePriority(input.priority()), next, now,
                draftId, projectId, user.id(), current.versionNo());
        if (changed != 1) throw new BusinessException("草稿已有新版本，请刷新后重试");
        var newContent = new LinkedHashMap<String, Object>();
        newContent.put("caseTitle", input.caseTitle().strip());
        newContent.put("moduleName", input.moduleName());
        newContent.put("precondition", input.precondition());
        newContent.put("steps", input.steps());
        newContent.put("expectedResult", input.expectedResult());
        newContent.put("priority", normalizePriority(input.priority()));
        jdbcTemplate.update("""
                INSERT INTO dsh_draft_revision(draft_id, version_no, content_json, content_hash,
                    request_key, changed_by, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, draftId, next, json(newContent), hash, input.idempotencyKey(), user.id(), now);
        return new RevisedDraft(draftId, next, hash);
    }

    public Map<String, Object> currentRevision(Long projectId, Long draftId, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        return jdbc.sql("""
                SELECT id, task_id, case_no, case_title, module_name, precondition, steps,
                    expected_result, priority, case_status, version_no
                FROM test_case_draft WHERE id = :id AND project_id = :projectId AND created_by = :userId
                """).param("id", draftId).param("projectId", projectId).param("userId", user.id())
                .query((rs, row) -> {
                    var result = new LinkedHashMap<String, Object>();
                    result.put("draftId", rs.getLong("id"));
                    result.put("taskId", rs.getLong("task_id"));
                    result.put("caseNo", rs.getString("case_no"));
                    result.put("caseTitle", rs.getString("case_title"));
                    result.put("moduleName", rs.getString("module_name"));
                    result.put("precondition", rs.getString("precondition"));
                    result.put("steps", rs.getString("steps"));
                    result.put("expectedResult", rs.getString("expected_result"));
                    result.put("priority", rs.getString("priority"));
                    result.put("caseStatus", rs.getString("case_status"));
                    result.put("versionNo", rs.getInt("version_no"));
                    return result;
                }).optional().orElseThrow(() -> new BusinessException("个人草稿不存在或无权访问"));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new BusinessException("草稿修订内容格式错误"); }
    }

    private static String normalizePriority(String priority) {
        if (priority == null) return "P2";
        return switch (priority.trim().toUpperCase()) {
            case "P0", "P1", "P2", "P3" -> priority.trim().toUpperCase();
            default -> "P2";
        };
    }

    List<ReferenceSnapshot> resolveSourceRefs(Long projectId, CurrentUser user, List<String> refs) {
        if (refs == null || refs.isEmpty()) return List.of();
        List<ReferenceSnapshot> snapshots = new ArrayList<>(refs.size());
        for (String ref : refs) {
            if (ref == null || !ref.matches("^(knowledge_document|test_case_asset|test_skill_template)#[1-9][0-9]*$")) {
                throw new BusinessException("来源引用格式无效");
            }
            int separator = ref.indexOf('#');
            long id;
            try { id = Long.parseLong(ref.substring(separator + 1)); }
            catch (NumberFormatException exception) { throw new BusinessException("来源引用格式无效"); }
            String type = ref.substring(0, separator);
            String sql = switch (type) {
                case "knowledge_document" -> """
                        SELECT content_hash FROM knowledge_document
                        WHERE id = :id AND project_id = :projectId AND review_status = 'APPROVED'
                          AND deprecated_at IS NULL
                        """;
                case "test_case_asset" -> """
                        SELECT version, case_title, module_name, precondition, steps, expected_result,
                               priority, source_refs_json FROM test_case_asset
                        WHERE id = :id AND project_id = :projectId AND case_status = 'SUBMITTED'
                          AND deprecated_at IS NULL
                          AND (case_scope IS NULL OR case_scope IN ('PROJECT', 'PUBLIC', 'SYSTEM')
                               OR (case_scope = 'PERSONAL' AND submitted_by = :userId))
                        """;
                case "test_skill_template" -> """
                        SELECT version, skill_name, applicable_scene, flow_steps, body
                        FROM test_skill_template
                        WHERE id = :id AND status = 'ACTIVE' AND deprecated_at IS NULL
                          AND (scope = 'SYSTEM'
                            OR (scope = 'PROJECT' AND project_id = :projectId AND review_status = 'APPROVED')
                            OR (scope = 'PUBLIC' AND review_status = 'APPROVED')
                            OR (scope = 'PERSONAL' AND project_id = :projectId AND created_by = :userId))
                        """;
                default -> throw new BusinessException("来源引用类型无效");
            };
            var snapshot = jdbc.sql(sql).param("id", id).param("projectId", projectId)
                    .param("userId", user.id()).query((rs, rowNum) -> switch (type) {
                        case "knowledge_document" -> new ReferenceSnapshot(ref, null, rs.getString("content_hash"));
                        case "test_case_asset" -> new ReferenceSnapshot(ref, rs.getInt("version"), sha256(String.join("\u001f",
                                safe(rs.getString("case_title")), safe(rs.getString("module_name")),
                                safe(rs.getString("precondition")), safe(rs.getString("steps")),
                                safe(rs.getString("expected_result")), safe(rs.getString("priority")),
                                safe(rs.getString("source_refs_json")))));
                        case "test_skill_template" -> new ReferenceSnapshot(ref, rs.getInt("version"), sha256(String.join("\u001f",
                                safe(rs.getString("skill_name")), safe(rs.getString("applicable_scene")),
                                safe(rs.getString("flow_steps")), safe(rs.getString("body")))));
                        default -> throw new BusinessException("来源引用类型无效");
                    }).optional();
            if (snapshot.isEmpty()) throw new BusinessException("来源引用不属于当前可用资产");
            snapshots.add(snapshot.get());
        }
        return snapshots;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static int length(String value) { return value == null ? 0 : value.length(); }
    private static String safe(String value) { return value == null ? "" : value; }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record DraftInput(Long workSessionId, String idempotencyKey, String requirementText, String caseTitle,
                             String moduleName, String precondition, String steps, String expectedResult,
                             String priority, String pointType, List<String> sourceRefs, Long captureSessionId) {}
    public record ReferenceSnapshot(String ref, Integer version, String contentHash) {}
    public record SavedDraft(Long draftId, String requestHash) {}
    public record DraftRevisionInput(Long workSessionId, Integer expectedVersion, String idempotencyKey,
                                     String caseTitle, String moduleName, String precondition, String steps,
                                     String expectedResult, String priority) {}
    public record RevisedDraft(Long draftId, int versionNo, String contentHash) {}
    private record DraftState(Long id, Long taskId, String caseStatus, int versionNo, String caseTitle,
                              String moduleName, String precondition, String steps, String expectedResult, String priority) {}
}

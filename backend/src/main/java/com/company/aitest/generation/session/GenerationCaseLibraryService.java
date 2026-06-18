package com.company.aitest.generation.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GenerationCaseLibraryService {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;

    public GenerationCaseLibraryService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TimeProvider timeProvider) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
    }

    public List<LocalCaseDraftView> list(Long projectId, CurrentUser user) {
        List<LocalCaseDraftView> result = new ArrayList<>();
        result.addAll(jdbc.sql("""
                SELECT *,
                       'GENERATION' AS source_type
                FROM test_case_draft
                WHERE project_id = :projectId
                  AND created_by = :createdBy
                """)
                .param("projectId", projectId)
                .param("createdBy", user.id())
                .query(this::mapGenerationDraft)
                .list());
        result.addAll(jdbc.sql("""
                SELECT -tgc.id AS id,
                       0 AS task_id,
                       tgc.project_id,
                       CONCAT('TRACE-', tgc.id) AS case_no,
                       tgc.case_title,
                       COALESCE(p.project_name, '') AS project_name,
                       tgc.module_name,
                       tgc.precondition,
                       tgc.steps,
                       tgc.expected_result,
                       tgc.priority,
                       tgc.case_type,
                       '轨迹回放法' AS design_method,
                       tgc.source_refs_json,
                       tgc.case_scope,
                       tgc.case_status,
                       tgc.user_id AS created_by,
                       tgc.created_at,
                       tgc.updated_at,
                       tgc.trace_session_id AS session_id,
                       'TRACE' AS source_type
                FROM trace_generated_case tgc
                LEFT JOIN project p ON p.id = tgc.project_id
                WHERE tgc.project_id = :projectId
                  AND tgc.user_id = :createdBy
                """)
                .param("projectId", projectId)
                .param("createdBy", user.id())
                .query(this::mapTraceDraft)
                .list());
        result.sort(Comparator
                .comparing(LocalCaseDraftView::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(LocalCaseDraftView::id, Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    public LocalCaseDraftView getOwnedDraft(Long projectId, Long draftId, CurrentUser user) {
        if (draftId != null && draftId < 0) {
            return getOwnedTraceDraft(projectId, Math.abs(draftId), user);
        }
        return getOwnedGenerationDraft(projectId, draftId, user);
    }

    private LocalCaseDraftView getOwnedGenerationDraft(Long projectId, Long draftId, CurrentUser user) {
        var list = jdbc.sql("""
                SELECT *,
                       'GENERATION' AS source_type
                FROM test_case_draft
                WHERE id = :id
                  AND project_id = :projectId
                  AND created_by = :createdBy
                """)
                .param("id", draftId)
                .param("projectId", projectId)
                .param("createdBy", user.id())
                .query(this::mapGenerationDraft)
                .list();
        if (list.isEmpty()) {
            throw new BusinessException("本地用例不存在");
        }
        return list.get(0);
    }

    private LocalCaseDraftView getOwnedTraceDraft(Long projectId, Long draftId, CurrentUser user) {
        var list = jdbc.sql("""
                SELECT -tgc.id AS id,
                       0 AS task_id,
                       tgc.project_id,
                       CONCAT('TRACE-', tgc.id) AS case_no,
                       COALESCE(tgc.case_title, CONCAT('轨迹草稿 #', tgc.id)) AS case_title,
                       COALESCE(p.project_name, '') AS project_name,
                       tgc.module_name,
                       tgc.precondition,
                       tgc.steps,
                       tgc.expected_result,
                       tgc.priority,
                       tgc.case_type,
                       '轨迹回放法' AS design_method,
                       tgc.source_refs_json,
                       tgc.case_scope,
                       tgc.case_status,
                       tgc.user_id AS created_by,
                       tgc.created_at,
                       tgc.updated_at,
                       tgc.trace_session_id AS session_id,
                       'TRACE' AS source_type
                FROM trace_generated_case tgc
                LEFT JOIN project p ON p.id = tgc.project_id
                WHERE tgc.id = :id
                  AND tgc.project_id = :projectId
                  AND tgc.user_id = :createdBy
                """)
                .param("id", draftId)
                .param("projectId", projectId)
                .param("createdBy", user.id())
                .query(this::mapTraceDraft)
                .list();
        if (list.isEmpty()) {
            throw new BusinessException("本地用例不存在");
        }
        return list.get(0);
    }

    @Transactional
    public LocalCaseDraftView update(Long projectId, Long draftId, UpdateLocalCaseCommand cmd, CurrentUser user) {
        LocalCaseDraftView draft = getOwnedDraft(projectId, draftId, user);
        if ("SUBMITTED".equalsIgnoreCase(draft.caseStatus())) {
            throw new BusinessException("已提交正式库的用例不可再编辑");
        }
        if ("TRACE".equalsIgnoreCase(draft.sourceType())) {
            return updateTraceDraft(projectId, draftId, cmd, user, draft);
        }
        return updateGenerationDraft(projectId, draftId, cmd, user, draft);
    }

    private LocalCaseDraftView updateGenerationDraft(Long projectId, Long draftId, UpdateLocalCaseCommand cmd,
                                                     CurrentUser user, LocalCaseDraftView draft) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                UPDATE test_case_draft
                SET case_title = ?,
                    module_name = ?,
                    precondition = ?,
                    steps = ?,
                    expected_result = ?,
                    priority = ?,
                    updated_at = ?
                WHERE id = ?
                  AND project_id = ?
                  AND created_by = ?
                """,
                firstNonNull(cmd.caseTitle(), draft.caseTitle()),
                firstNonNull(cmd.moduleName(), draft.moduleName()),
                firstNonNull(cmd.precondition(), draft.precondition()),
                firstNonNull(cmd.steps(), draft.steps()),
                firstNonNull(cmd.expectedResult(), draft.expectedResult()),
                normalizePriority(firstNonNull(cmd.priority(), draft.priority())),
                now,
                draftId,
                projectId,
                user.id());
        return getOwnedDraft(projectId, draftId, user);
    }

    private LocalCaseDraftView updateTraceDraft(Long projectId, Long draftId, UpdateLocalCaseCommand cmd,
                                                CurrentUser user, LocalCaseDraftView draft) {
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                UPDATE trace_generated_case
                SET case_title = ?,
                    module_name = ?,
                    precondition = ?,
                    steps = ?,
                    expected_result = ?,
                    priority = ?,
                    updated_at = ?
                WHERE id = ?
                  AND project_id = ?
                  AND user_id = ?
                """,
                firstNonNull(cmd.caseTitle(), draft.caseTitle()),
                firstNonNull(cmd.moduleName(), draft.moduleName()),
                firstNonNull(cmd.precondition(), draft.precondition()),
                firstNonNull(cmd.steps(), draft.steps()),
                firstNonNull(cmd.expectedResult(), draft.expectedResult()),
                normalizePriority(firstNonNull(cmd.priority(), draft.priority())),
                now,
                Math.abs(draftId),
                projectId,
                user.id());
        return getOwnedDraft(projectId, draftId, user);
    }

    @Transactional
    public LocalCaseDraftView confirm(Long projectId, Long draftId, CurrentUser user) {
        LocalCaseDraftView draft = getOwnedDraft(projectId, draftId, user);
        ensureNotSubmitted(draft);
        if ("TRACE".equalsIgnoreCase(draft.sourceType())) {
            updateTraceStatus(projectId, draftId, user.id(), "CONFIRMED");
        } else {
            updateGenerationStatus(projectId, draftId, user.id(), "CONFIRMED");
        }
        return getOwnedDraft(projectId, draftId, user);
    }

    @Transactional
    public LocalCaseDraftView deprecate(Long projectId, Long draftId, CurrentUser user) {
        LocalCaseDraftView draft = getOwnedDraft(projectId, draftId, user);
        ensureNotSubmitted(draft);
        if ("TRACE".equalsIgnoreCase(draft.sourceType())) {
            updateTraceStatus(projectId, draftId, user.id(), "DEPRECATED");
        } else {
            updateGenerationStatus(projectId, draftId, user.id(), "DEPRECATED");
        }
        return getOwnedDraft(projectId, draftId, user);
    }

    @Transactional
    public void submitToFormal(Long projectId, Long draftId, CurrentUser user) {
        LocalCaseDraftView draft = getOwnedDraft(projectId, draftId, user);
        if ("SUBMITTED".equalsIgnoreCase(draft.caseStatus())) {
            throw new BusinessException("该用例已提交到正式库");
        }
        if ("DEPRECATED".equalsIgnoreCase(draft.caseStatus())) {
            throw new BusinessException("已弃用的用例不能提交到正式库");
        }
        if ("TRACE".equalsIgnoreCase(draft.sourceType())) {
            submitTraceDraftToFormal(projectId, draftId, user, draft);
            return;
        }
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO test_case_asset(project_id, source_task_id, source_draft_id, case_no, case_title, project_name,
                  module_name, precondition, steps, expected_result, priority, case_type, design_method, source_refs_json,
                  created_at, updated_at, case_scope, case_status, submitted_by, submitted_at,
                  source_trace_group_id, source_trace_session_id, source_issue_clip_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROJECT', 'SUBMITTED', ?, ?, NULL, NULL, NULL)
                """,
                draft.projectId(),
                draft.taskId(),
                draft.id(),
                draft.caseNo(),
                draft.caseTitle(),
                draft.projectName(),
                draft.moduleName(),
                draft.precondition(),
                draft.steps(),
                draft.expectedResult(),
                normalizePriority(draft.priority()),
                firstNonBlank(draft.caseType(), "FUNCTIONAL"),
                firstNonBlank(draft.designMethod(), "LLM生成"),
                draft.sourceRefsJson(),
                now,
                now,
                user.id(),
                now);
        updateGenerationStatus(projectId, draftId, user.id(), "SUBMITTED");
    }

    private void submitTraceDraftToFormal(Long projectId, Long draftId, CurrentUser user, LocalCaseDraftView draft) {
        LocalDateTime now = timeProvider.now();
        Long traceGroupId = extractTraceRef(draft.sourceRefsJson(), "traceGroupId");
        Long traceSessionId = extractTraceRef(draft.sourceRefsJson(), "traceSessionId");
        Long issueClipId = extractTraceRef(draft.sourceRefsJson(), "issueClipId");
        jdbcTemplate.update("""
                INSERT INTO test_case_asset(project_id, source_task_id, source_draft_id, case_no, case_title, project_name,
                  module_name, precondition, steps, expected_result, priority, case_type, design_method, source_refs_json,
                  created_at, updated_at, case_scope, case_status, submitted_by, submitted_at,
                  source_trace_group_id, source_trace_session_id, source_issue_clip_id)
                VALUES (?, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROJECT', 'SUBMITTED', ?, ?, ?, ?, ?)
                """,
                draft.projectId(),
                firstNonBlank(draft.caseNo(), "TRACE-" + Math.abs(draftId)),
                draft.caseTitle(),
                draft.projectName(),
                draft.moduleName(),
                draft.precondition(),
                draft.steps(),
                draft.expectedResult(),
                normalizePriority(draft.priority()),
                firstNonBlank(draft.caseType(), "FUNCTIONAL"),
                firstNonBlank(draft.designMethod(), "轨迹回放法"),
                draft.sourceRefsJson(),
                now,
                now,
                user.id(),
                now,
                traceGroupId,
                traceSessionId,
                issueClipId);
        updateTraceStatus(projectId, draftId, user.id(), "SUBMITTED");
    }

    private void updateGenerationStatus(Long projectId, Long draftId, Long userId, String status) {
        jdbcTemplate.update("""
                UPDATE test_case_draft
                SET case_status = ?,
                    asset_status = ?,
                    updated_at = ?
                WHERE id = ?
                  AND project_id = ?
                  AND created_by = ?
                """, status, status, timeProvider.now(), draftId, projectId, userId);
    }

    private void updateTraceStatus(Long projectId, Long draftId, Long userId, String status) {
        jdbcTemplate.update("""
                UPDATE trace_generated_case
                SET case_status = ?,
                    case_scope = ?,
                    updated_at = ?
                WHERE id = ?
                  AND project_id = ?
                  AND user_id = ?
                """,
                status,
                "SUBMITTED".equalsIgnoreCase(status) ? "PROJECT" : "PERSONAL",
                timeProvider.now(),
                Math.abs(draftId),
                projectId,
                userId);
    }

    private void ensureNotSubmitted(LocalCaseDraftView draft) {
        if ("SUBMITTED".equalsIgnoreCase(draft.caseStatus())) {
            throw new BusinessException("该用例已提交到正式库");
        }
    }

    private String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return "P2";
        }
        String normalized = priority.trim().toUpperCase();
        return switch (normalized) {
            case "P0", "P1", "P2", "P3", "P4" -> normalized;
            default -> "P2";
        };
    }

    private String firstNonNull(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private Long extractTraceRef(String sourceRefsJson, String key) {
        if (sourceRefsJson == null || sourceRefsJson.isBlank()) {
            return null;
        }
        try {
            var map = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    sourceRefsJson, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
            Object value = map.get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                return Long.parseLong(text);
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private LocalCaseDraftView mapGenerationDraft(ResultSet rs, int rowNum) throws SQLException {
        return new LocalCaseDraftView(
                rs.getLong("id"),
                rs.getLong("task_id"),
                rs.getLong("project_id"),
                rs.getString("case_no"),
                rs.getString("case_title"),
                rs.getString("project_name"),
                rs.getString("module_name"),
                rs.getString("precondition"),
                rs.getString("steps"),
                rs.getString("expected_result"),
                rs.getString("priority"),
                rs.getString("case_type"),
                rs.getString("design_method"),
                rs.getString("source_refs_json"),
                rs.getString("case_scope"),
                rs.getString("case_status"),
                rs.getLong("created_by"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getObject("session_id") == null ? null : rs.getLong("session_id"),
                rs.getString("source_type") == null ? "GENERATION" : rs.getString("source_type"));
    }

    private LocalCaseDraftView mapTraceDraft(ResultSet rs, int rowNum) throws SQLException {
        return new LocalCaseDraftView(
                rs.getLong("id"),
                rs.getLong("task_id"),
                rs.getLong("project_id"),
                rs.getString("case_no"),
                rs.getString("case_title"),
                rs.getString("project_name"),
                rs.getString("module_name"),
                rs.getString("precondition"),
                rs.getString("steps"),
                rs.getString("expected_result"),
                rs.getString("priority"),
                rs.getString("case_type"),
                rs.getString("design_method"),
                rs.getString("source_refs_json"),
                rs.getString("case_scope"),
                rs.getString("case_status"),
                rs.getLong("created_by"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getObject("session_id") == null ? null : rs.getLong("session_id"),
                rs.getString("source_type"));
    }

    public record UpdateLocalCaseCommand(
            String caseTitle,
            String moduleName,
            String precondition,
            String steps,
            String expectedResult,
            String priority
    ) {
    }

    public record LocalCaseDraftView(
            Long id,
            Long taskId,
            Long projectId,
            String caseNo,
            String caseTitle,
            String projectName,
            String moduleName,
            String precondition,
            String steps,
            String expectedResult,
            String priority,
            String caseType,
            String designMethod,
            String sourceRefsJson,
            String caseScope,
            String caseStatus,
            Long createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            Long sessionId,
            String sourceType
    ) {
    }
}

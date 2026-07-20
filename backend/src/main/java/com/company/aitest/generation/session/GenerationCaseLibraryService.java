package com.company.aitest.generation.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * The library list is intentionally a light-weight, paged projection. Long steps, expected
     * results and source JSON are loaded only through getOwnedDraft when a user opens a detail.
     */
    public LocalCaseDraftPage listPage(Long projectId, int page, int size, String keyword,
                                       List<String> modules, List<String> priorities, List<String> statuses,
                                       List<String> sources, CurrentUser user) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(10, Math.min(size, 100));
        int offset = safePage * safeSize;
        String unionSql = """
                (
                    SELECT id, task_id, project_id, case_no, case_title, project_name, module_name,
                           NULL AS precondition, NULL AS steps, NULL AS expected_result,
                           priority, case_type, design_method, NULL AS source_refs_json,
                           case_scope, case_status, created_by, created_at, updated_at, session_id,
                           'GENERATION' AS source_type
                    FROM test_case_draft
                    WHERE project_id = :projectId AND created_by = :createdBy
                    UNION ALL
                    SELECT -tgc.id AS id, 0 AS task_id, tgc.project_id, CONCAT('TRACE-', tgc.id) AS case_no,
                           COALESCE(tgc.case_title, CONCAT('轨迹草稿 #', tgc.id)) AS case_title,
                           COALESCE(p.project_name, '') AS project_name, tgc.module_name,
                           NULL AS precondition, NULL AS steps, NULL AS expected_result,
                           tgc.priority, tgc.case_type, '轨迹回放法' AS design_method, NULL AS source_refs_json,
                           tgc.case_scope, tgc.case_status, tgc.user_id AS created_by,
                           tgc.created_at, tgc.updated_at, tgc.trace_session_id AS session_id,
                           'TRACE' AS source_type
                    FROM trace_generated_case tgc
                    LEFT JOIN project p ON p.id = tgc.project_id
                    WHERE tgc.project_id = :projectId AND tgc.user_id = :createdBy
                )
                """;
        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("createdBy", user.id());
        String filterSql = appendLocalCaseFilters(new StringBuilder(" WHERE 1=1"), params,
                keyword, modules, priorities, statuses, sources).toString();
        Integer total = jdbc.sql("SELECT COUNT(*) FROM " + unionSql + " local_case" + filterSql)
                .params(params).query(Integer.class).single();
        params.put("limit", safeSize);
        params.put("offset", offset);
        List<LocalCaseDraftView> items = jdbc.sql(buildLocalCasePageSql(unionSql, filterSql))
                .params(params)
                .query(this::mapGenerationDraft).list();
        String moduleSql = "SELECT DISTINCT module_name FROM " + unionSql + " local_case "
                + "WHERE module_name IS NOT NULL AND module_name <> '' ORDER BY module_name";
        List<String> moduleOptions = jdbc.sql(moduleSql)
                .param("projectId", projectId).param("createdBy", user.id())
                .query(String.class).list();
        return new LocalCaseDraftPage(items, total == null ? 0 : total, safePage, safeSize, moduleOptions);
    }

    String buildLocalCasePageSql(String unionSql, String filterSql) {
        return String.join("\n",
                "SELECT * FROM " + unionSql + " local_case",
                filterSql.trim(),
                "ORDER BY updated_at DESC, id DESC",
                "LIMIT :limit OFFSET :offset");
    }

    StringBuilder appendLocalCaseFilters(StringBuilder sql, Map<String, Object> params,
                                         String keyword, List<String> modules, List<String> priorities,
                                         List<String> statuses, List<String> sources) {
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (case_title LIKE :keyword OR case_no LIKE :keyword OR module_name LIKE :keyword")
                    .append(" OR case_type LIKE :keyword OR case_scope LIKE :keyword OR source_type LIKE :keyword)");
            params.put("keyword", "%" + keyword.trim() + "%");
        }
        appendInFilter(sql, params, "module_name", "modules", modules);
        appendInFilter(sql, params, "priority", "priorities", priorities);
        appendInFilter(sql, params, "case_status", "statuses", statuses);
        appendInFilter(sql, params, "source_type", "sources", sources);
        return sql;
    }

    private void appendInFilter(StringBuilder sql, Map<String, Object> params, String column,
                                String parameter, List<String> values) {
        List<String> normalized = values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        if (!normalized.isEmpty()) {
            sql.append(" AND ").append(column).append(" IN (:").append(parameter).append(")");
            params.put(parameter, normalized);
        }
    }

    public LocalCaseDraftView getOwnedDraft(Long projectId, Long draftId, CurrentUser user) {
        if (draftId != null && draftId < 0) {
            return getOwnedTraceDraft(projectId, Math.abs(draftId), user);
        }
        return getOwnedGenerationDraft(projectId, draftId, user);
    }

    @Transactional
    public LocalCaseDraftView duplicate(Long projectId, Long draftId, CurrentUser user) {
        LocalCaseDraftView source = getOwnedDraft(projectId, draftId, user);
        ensureNotSubmitted(source);
        LocalDateTime now = timeProvider.now();
        if ("TRACE".equalsIgnoreCase(source.sourceType())) {
            jdbcTemplate.update("""
                    INSERT INTO trace_generated_case(project_id, user_id, trace_group_id, trace_session_id, issue_clip_id,
                      case_type, case_title, module_name, precondition, steps, expected_result, priority,
                      case_scope, case_status, source_refs_json, model_config_id, prompt_snapshot, created_at, updated_at)
                    SELECT project_id, user_id, trace_group_id, trace_session_id, issue_clip_id,
                      case_type, CONCAT('副本 - ', case_title), module_name, precondition, steps, expected_result, priority,
                      'PERSONAL', 'DRAFT', source_refs_json, model_config_id, prompt_snapshot, ?, ?
                    FROM trace_generated_case
                    WHERE id = ? AND project_id = ? AND user_id = ?
                    """, now, now, Math.abs(draftId), projectId, user.id());
            Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
            return getOwnedTraceDraft(projectId, id, user);
        }
        jdbcTemplate.update("""
                INSERT INTO test_case_draft(task_id, project_id, test_point_id, case_no, case_title, project_name,
                  module_name, precondition, steps, expected_result, priority, case_type, design_method,
                  source_refs_json, is_assumption, assumption_note, compliance_mark, user_feedback, quality_status,
                  version_no, asset_status, case_scope, case_status, created_by, created_at, updated_at, session_id,
                  analysis_id, analysis_version, analysis_sub_version)
                SELECT task_id, project_id, test_point_id, CONCAT(case_no, '-COPY-', id), CONCAT('副本 - ', case_title), project_name,
                  module_name, precondition, steps, expected_result, priority, case_type, design_method,
                  source_refs_json, is_assumption, assumption_note, compliance_mark, user_feedback, quality_status,
                  1, 'DRAFT', 'PERSONAL', 'DRAFT', created_by, ?, ?, session_id,
                  analysis_id, analysis_version, analysis_sub_version
                FROM test_case_draft
                WHERE id = ? AND project_id = ? AND created_by = ?
                """, now, now, draftId, projectId, user.id());
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return getOwnedGenerationDraft(projectId, id, user);
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
        int deleted;
        if ("TRACE".equalsIgnoreCase(draft.sourceType())) {
            deleted = jdbcTemplate.update("""
                    DELETE FROM trace_generated_case
                    WHERE id = ? AND project_id = ? AND user_id = ?
                    """, Math.abs(draftId), projectId, user.id());
        } else {
            deleted = jdbcTemplate.update("""
                    DELETE FROM test_case_draft
                    WHERE id = ? AND project_id = ? AND created_by = ?
                    """, draftId, projectId, user.id());
        }
        if (deleted != 1) {
            throw new BusinessException("舍弃草稿失败");
        }
        // Keep the legacy endpoint response shape for older clients; the row has been physically removed.
        return draft;
    }

    @Transactional
    public BatchOperationResult batchConfirm(Long projectId, List<Long> draftIds, CurrentUser user) {
        List<Long> ids = normalizeBatchIds(draftIds);
        for (Long draftId : ids) {
            confirm(projectId, draftId, user);
        }
        return new BatchOperationResult(ids.size());
    }

    @Transactional
    public BatchOperationResult batchDeprecate(Long projectId, List<Long> draftIds, CurrentUser user) {
        List<Long> ids = normalizeBatchIds(draftIds);
        for (Long draftId : ids) {
            deprecate(projectId, draftId, user);
        }
        return new BatchOperationResult(ids.size());
    }

    @Transactional
    public BatchOperationResult batchSubmitToFormal(Long projectId, List<Long> draftIds, CurrentUser user) {
        List<Long> ids = normalizeBatchIds(draftIds);
        for (Long draftId : ids) {
            submitToFormal(projectId, draftId, user);
        }
        return new BatchOperationResult(ids.size());
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

    private List<Long> normalizeBatchIds(List<Long> draftIds) {
        List<Long> ids = draftIds == null ? List.of() : draftIds.stream()
                .filter(id -> id != null && id != 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            throw new BusinessException("请至少选择一条用例");
        }
        if (ids.size() > 200) {
            throw new BusinessException("一次最多批量处理 200 条用例");
        }
        return ids;
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

    public record LocalCaseDraftPage(List<LocalCaseDraftView> items, int total, int page, int size,
                                     List<String> moduleOptions) {
    }

    public record BatchOperationResult(int affectedCount) {
    }
}

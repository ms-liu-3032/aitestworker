package com.company.aitest.dsh;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.project.ProjectAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DshWorkSessionService {
    private static final Set<String> STAGES = Set.of("REQUIREMENT_ANALYSIS", "CLARIFICATION", "TEST_POINTS",
            "COVERAGE_PLAN", "CASE_DESIGN", "QUALITY_REVIEW");
    private static final List<String> REQUIRED_DRAFT_STAGES = List.of("REQUIREMENT_ANALYSIS", "TEST_POINTS",
            "COVERAGE_PLAN", "CASE_DESIGN", "QUALITY_REVIEW");
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TimeProvider clock;
    private final ProjectAccessService access;
    private final ObjectMapper mapper;

    public DshWorkSessionService(JdbcClient jdbc, JdbcTemplate template, TimeProvider clock,
                                 ProjectAccessService access, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.template = template;
        this.clock = clock;
        this.access = access;
        this.mapper = mapper;
    }

    @Transactional
    public WorkSession start(Long projectId, String requirementText, String tomMode, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        if (requirementText == null || requirementText.isBlank() || requirementText.length() > 100_000) {
            throw new BusinessException("需求内容不能为空且最多 100000 字");
        }
        if (tomMode == null || !Set.of("DIRECT", "PROJECT_TOM", "PROJECT_AND_SYSTEM_TOM").contains(tomMode)) {
            throw new BusinessException("请先明确选择 TOM 模式：DIRECT、PROJECT_TOM 或 PROJECT_AND_SYSTEM_TOM");
        }
        LocalDateTime now = clock.now();
        String title = requirementText.strip().replace('\n', ' ');
        title = title.substring(0, Math.min(title.length(), 120));
        template.update("""
                INSERT INTO generation_task(project_id, task_name, requirement_text, current_stage, status,
                    task_type, run_status, created_by, created_at, updated_at)
                VALUES (?, ?, ?, 'CREATED', 'CREATED', 'DSH_EXTERNAL', 'SUCCEEDED', ?, ?, ?)
                """, projectId, title, requirementText, user.id(), now, now);
        Long taskId = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        template.update("""
                INSERT INTO dsh_work_session(project_id, user_id, task_id, requirement_text, tom_mode, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, projectId, user.id(), taskId, requirementText, tomMode, now, now);
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return new WorkSession(id, projectId, taskId, requirementText, tomMode, "ACTIVE");
    }

    public WorkSession get(Long projectId, Long sessionId, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        return jdbc.sql("""
                SELECT id, project_id, task_id, requirement_text, tom_mode, status FROM dsh_work_session
                WHERE id = :id AND project_id = :projectId AND user_id = :userId
                """).param("id", sessionId).param("projectId", projectId).param("userId", user.id())
                .query((rs, rowNum) -> new WorkSession(rs.getLong("id"), rs.getLong("project_id"),
                        rs.getLong("task_id"), rs.getString("requirement_text"), rs.getString("tom_mode"), rs.getString("status")))
                .optional().orElseThrow(() -> new BusinessException("DSH 测试任务不存在或无权访问"));
    }

    public List<StageSnapshot> stages(Long projectId, Long sessionId, CurrentUser user) {
        get(projectId, sessionId, user);
        return jdbc.sql("""
                SELECT s.id, s.stage_key, s.version_no, s.payload_json, s.content_hash, s.status
                FROM dsh_stage_snapshot s
                JOIN (SELECT stage_key, MAX(version_no) version_no FROM dsh_stage_snapshot
                      WHERE work_session_id = :sessionId GROUP BY stage_key) latest
                  ON latest.stage_key = s.stage_key AND latest.version_no = s.version_no
                WHERE s.work_session_id = :sessionId
                ORDER BY s.id
                """).param("sessionId", sessionId).query(this::map).list();
    }

    @Transactional
    public StageSnapshot saveStage(Long projectId, Long sessionId, String stageKey, String payloadJson,
                                   CurrentUser user) {
        WorkSession session = get(projectId, sessionId, user);
        if (!"ACTIVE".equals(session.status())) throw new BusinessException("测试任务已关闭");
        jdbc.sql("SELECT id FROM dsh_work_session WHERE id = :id AND user_id = :userId FOR UPDATE")
                .param("id", sessionId).param("userId", user.id()).query(Long.class).single();
        validate(stageKey, payloadJson);
        String prerequisite = switch (stageKey) {
            case "TEST_POINTS" -> "REQUIREMENT_ANALYSIS";
            case "COVERAGE_PLAN" -> "TEST_POINTS";
            case "CASE_DESIGN" -> "COVERAGE_PLAN";
            case "QUALITY_REVIEW" -> "CASE_DESIGN";
            default -> null;
        };
        if (prerequisite != null) {
            StageSnapshot previous = latest(sessionId, prerequisite, false);
            if (previous == null || "STALE".equals(previous.status())) {
                throw new BusinessException("请先完成上一阶段：" + prerequisite);
            }
        }
        validateMethodology(sessionId, stageKey, payloadJson);
        String canonical;
        try { canonical = mapper.writeValueAsString(mapper.readTree(payloadJson)); }
        catch (JsonProcessingException exception) { throw new BusinessException("阶段内容必须是有效 JSON"); }
        String hash = sha256(canonical);
        var latest = latest(sessionId, stageKey, false);
        if (latest != null && hash.equals(latest.contentHash()) && !"STALE".equals(latest.status())) return latest;
        int version = latest == null ? 1 : latest.versionNo() + 1;
        LocalDateTime now = clock.now();
        for (String downstream : downstreamStages(stageKey)) {
            template.update("""
                    UPDATE dsh_stage_snapshot SET status = 'STALE'
                    WHERE work_session_id = ? AND stage_key = ? AND status <> 'STALE'
                    """, sessionId, downstream);
        }
        template.update("""
                INSERT INTO dsh_stage_snapshot(work_session_id, stage_key, version_no, payload_json,
                    content_hash, status, created_at)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', ?)
                """, sessionId, stageKey, version, canonical, hash, now);
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        template.update("UPDATE dsh_work_session SET updated_at = ? WHERE id = ?", now, sessionId);
        return new StageSnapshot(id, stageKey, version, canonical, hash, "DRAFT");
    }

    @Transactional
    public StageSnapshot confirm(Long projectId, Long sessionId, String stageKey, String reviewedHash,
                                 CurrentUser user) {
        get(projectId, sessionId, user);
        if (!STAGES.contains(stageKey) || reviewedHash == null || reviewedHash.isBlank()) {
            throw new BusinessException("阶段或审核指纹无效");
        }
        StageSnapshot latest = latest(sessionId, stageKey, true);
        if (latest == null || "STALE".equals(latest.status()) || !latest.contentHash().equals(reviewedHash)) {
            throw new BusinessException("阶段内容已变化，请重新查看");
        }
        if ("CONFIRMED".equals(latest.status())) return latest;
        LocalDateTime now = clock.now();
        template.update("UPDATE dsh_stage_snapshot SET status = 'CONFIRMED', confirmed_at = ? WHERE id = ?",
                now, latest.id());
        return new StageSnapshot(latest.id(), stageKey, latest.versionNo(), latest.payloadJson(),
                latest.contentHash(), "CONFIRMED");
    }

    public Long taskIdForDraft(Long projectId, Long sessionId, CurrentUser user) {
        WorkSession session = get(projectId, sessionId, user);
        for (String stage : REQUIRED_DRAFT_STAGES) {
            StageSnapshot snapshot = latest(sessionId, stage, false);
            if (snapshot == null || "STALE".equals(snapshot.status())) {
                throw new BusinessException("请先完成测试阶段：" + stage);
            }
        }
        StageSnapshot points = latest(sessionId, "TEST_POINTS", false);
        StageSnapshot coverage = latest(sessionId, "COVERAGE_PLAN", false);
        StageSnapshot cases = latest(sessionId, "CASE_DESIGN", false);
        DshMethodologyPolicy.validatePoints(parse(points.payloadJson()));
        DshMethodologyPolicy.validateCases(parse(cases.payloadJson()), parse(points.payloadJson()),
                parse(coverage.payloadJson()));
        return session.taskId();
    }

    public BatchValidation validateCaseBatch(Long projectId, Long sessionId, List<String> pointIds,
                                             String payloadJson, CurrentUser user) {
        WorkSession session = get(projectId, sessionId, user);
        if (!"ACTIVE".equals(session.status())) throw new BusinessException("测试任务已关闭");
        if (pointIds == null || pointIds.isEmpty() || pointIds.size() > 5
                || pointIds.stream().anyMatch(id -> id == null || !id.matches("TP[1-9][0-9]*"))
                || new HashSet<>(pointIds).size() != pointIds.size()) {
            throw new BusinessException("每批请选择 1 至 5 个不重复的测试点编号");
        }
        if (payloadJson == null || payloadJson.length() > 100_000) throw new BusinessException("用例批次内容无效或过长");
        StageSnapshot points = latest(sessionId, "TEST_POINTS", false);
        StageSnapshot coverage = latest(sessionId, "COVERAGE_PLAN", false);
        if (points == null || coverage == null || "STALE".equals(points.status())
                || "STALE".equals(coverage.status())) {
            throw new BusinessException("请先保存有效的测试点和覆盖计划");
        }
        JsonNode cases = parse(payloadJson);
        DshMethodologyPolicy.validateCasesForPoints(cases, parse(points.payloadJson()),
                parse(coverage.payloadJson()), Set.copyOf(pointIds));
        return new BatchValidation(List.copyOf(pointIds), cases.path("cases").size(), "FunctionalTestDesignPolicy");
    }

    public DraftMethodology requireDraftMatchesDesign(Long projectId, Long sessionId, String title,
                                                      String module, String precondition, String steps,
                                                      String expected, CurrentUser user) {
        get(projectId, sessionId, user);
        StageSnapshot cases = latest(sessionId, "CASE_DESIGN", false);
        JsonNode designed = cases == null || "STALE".equals(cases.status()) ? null
                : DshMethodologyPolicy.findDraft(parse(cases.payloadJson()), title, module, precondition, steps, expected);
        if (designed == null) {
            throw new BusinessException("草稿必须来自本轮已校验的用例设计，修改用例请先更新设计阶段");
        }
        List<String> methods = new java.util.ArrayList<>();
        designed.path("designMethods").forEach(method -> methods.add(method.asText()));
        return new DraftMethodology(designed.path("scenarioType").asText(), String.join(" + ", methods));
    }

    private void validateMethodology(Long sessionId, String stageKey, String payloadJson) {
        if ("COVERAGE_PLAN".equals(stageKey)) {
            DshMethodologyPolicy.validateCoverage(parse(payloadJson),
                    parse(latest(sessionId, "TEST_POINTS", false).payloadJson()));
        } else if ("CASE_DESIGN".equals(stageKey)) {
            DshMethodologyPolicy.validateCases(parse(payloadJson),
                    parse(latest(sessionId, "TEST_POINTS", false).payloadJson()),
                    parse(latest(sessionId, "COVERAGE_PLAN", false).payloadJson()));
        }
    }

    private JsonNode parse(String json) {
        try { return mapper.readTree(json); }
        catch (JsonProcessingException exception) { throw new BusinessException("阶段内容必须是有效 JSON"); }
    }

    private static List<String> downstreamStages(String stageKey) {
        return switch (stageKey) {
            case "REQUIREMENT_ANALYSIS", "CLARIFICATION" -> List.of("TEST_POINTS", "COVERAGE_PLAN", "CASE_DESIGN", "QUALITY_REVIEW");
            case "TEST_POINTS" -> List.of("COVERAGE_PLAN", "CASE_DESIGN", "QUALITY_REVIEW");
            case "COVERAGE_PLAN" -> List.of("CASE_DESIGN", "QUALITY_REVIEW");
            case "CASE_DESIGN" -> List.of("QUALITY_REVIEW");
            default -> List.of();
        };
    }

    private StageSnapshot latest(Long sessionId, String stageKey, boolean lock) {
        String sql = """
                SELECT id, stage_key, version_no, payload_json, content_hash, status
                FROM dsh_stage_snapshot WHERE work_session_id = :sessionId AND stage_key = :stageKey
                ORDER BY version_no DESC LIMIT 1
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.sql(sql).param("sessionId", sessionId).param("stageKey", stageKey)
                .query(this::map).optional().orElse(null);
    }

    private StageSnapshot map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new StageSnapshot(rs.getLong("id"), rs.getString("stage_key"), rs.getInt("version_no"),
                rs.getString("payload_json"), rs.getString("content_hash"), rs.getString("status"));
    }

    void validate(String stageKey, String payloadJson) {
        if (!STAGES.contains(stageKey)) throw new BusinessException("未知 DSH 测试阶段");
        if (payloadJson == null || payloadJson.length() > 100_000) throw new BusinessException("阶段内容最多 100000 字");
        JsonNode payload;
        try { payload = mapper.readTree(payloadJson); }
        catch (JsonProcessingException exception) { throw new BusinessException("阶段内容必须是有效 JSON"); }
        if (payload == null || !payload.isObject()) throw new BusinessException("阶段内容必须是 JSON 对象");
        switch (stageKey) {
            case "REQUIREMENT_ANALYSIS" -> requireText(payload, "summary");
            case "CLARIFICATION" -> requireArray(payload, "answers");
            case "TEST_POINTS" -> {
                requireNonEmptyArray(payload, "points");
                DshMethodologyPolicy.validatePoints(payload);
            }
            case "COVERAGE_PLAN" -> requireNonEmptyArray(payload, "items");
            case "CASE_DESIGN" -> requireNonEmptyArray(payload, "cases");
            case "QUALITY_REVIEW" -> requireArray(payload, "findings");
            default -> throw new BusinessException("未知 DSH 测试阶段");
        }
    }

    private static void requireText(JsonNode payload, String key) {
        if (!payload.path(key).isTextual() || payload.path(key).asText().isBlank()) {
            throw new BusinessException(key + " 不能为空");
        }
    }

    private static void requireArray(JsonNode payload, String key) {
        if (!payload.path(key).isArray()) throw new BusinessException(key + " 必须是数组");
    }

    private static void requireNonEmptyArray(JsonNode payload, String key) {
        requireArray(payload, key);
        if (payload.path(key).isEmpty()) throw new BusinessException(key + " 不能为空");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record WorkSession(Long id, Long projectId, Long taskId, String requirementText, String tomMode, String status) {}
    public record DraftMethodology(String scenarioType, String designMethod) {}
    public record BatchValidation(List<String> validatedPointIds, int caseCount, String ruleSource) {}
    public record StageSnapshot(Long id, String stageKey, int versionNo, String payloadJson,
                                String contentHash, String status) {}
}

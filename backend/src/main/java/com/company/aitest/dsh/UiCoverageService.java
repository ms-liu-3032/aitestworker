package com.company.aitest.dsh;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.project.ProjectAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A server-owned, evidence-backed verdict for a UI task. A case run is never a task verdict. */
@Service
public class UiCoverageService {
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final ProjectAccessService access;
    private final TimeProvider clock;
    private final ObjectMapper mapper;

    public UiCoverageService(JdbcClient jdbc, JdbcTemplate template, ProjectAccessService access,
                             TimeProvider clock, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.template = template;
        this.access = access;
        this.clock = clock;
        this.mapper = mapper;
    }

    public record TaskInput(String taskKey, String targetOrigin) {}
    public record ObligationInput(String ruleCode, String fieldKey, String pagePath, String fieldLabel,
                                  String sourceBasis, String applicability) {}
    public record EvidenceInput(Long positiveRunId, Long negativeRunId) {}
    public record RuleInput(String ruleCode, String title, String checkKind) {}
    public record Rule(Long id, Long projectId, String ruleCode, int versionNo, String title,
                       String checkKind, String status) {}
    public record RuleDecision(String applicability, String reason) {}
    public record TaskRule(String ruleCode, int ruleVersion, String checkKind,
                           String applicability, String reason, String status) {}
    public record Obligation(Long id, String ruleCode, String fieldKey, String pagePath, String fieldLabel,
                             String sourceBasis, String applicability, Long positiveRunId,
                             Long negativeRunId, String status) {}
    public record Verdict(Long taskId, String taskKey, String targetOrigin, String inventoryStatus,
                          String result, int requiredCount, int coveredCount,
                          List<TaskRule> rules, List<Obligation> obligations) {}

    @Transactional
    public Verdict start(Long projectId, TaskInput input, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        if (input == null || !key(input.taskKey()) || input.targetOrigin() == null)
            throw new BusinessException("UI 覆盖任务标识或目标地址无效");
        URI origin;
        try { origin = URI.create(input.targetOrigin()); }
        catch (RuntimeException ex) { throw new BusinessException("UI 目标地址无效"); }
        if (!Set.of("http", "https").contains(origin.getScheme()) || origin.getHost() == null
                || origin.getRawPath() != null && !origin.getRawPath().isEmpty() && !"/".equals(origin.getRawPath())
                || origin.getRawQuery() != null || origin.getRawFragment() != null || origin.getUserInfo() != null)
            throw new BusinessException("UI 目标地址须为 HTTP/HTTPS 站点根地址");
        var existing = jdbc.sql("SELECT id, target_origin FROM ui_coverage_task WHERE project_id = :project AND created_by = :user AND task_key = :key")
                .param("project", projectId).param("user", user.id()).param("key", input.taskKey())
                .query((rs, row) -> new Object[] {rs.getLong(1), rs.getString(2)}).list();
        if (!existing.isEmpty()) {
            if (!Objects.equals(existing.get(0)[1], input.targetOrigin())) throw new BusinessException("任务标识已绑定其他目标站点");
            return verdict(projectId, (Long) existing.get(0)[0], user);
        }
        template.update("INSERT INTO ui_coverage_task(project_id, task_key, target_origin, created_by, created_at) VALUES (?, ?, ?, ?, ?)",
                projectId, input.taskKey(), input.targetOrigin(), user.id(), clock.now());
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        for (Rule rule : effectiveRules(projectId, user)) template.update("""
                INSERT INTO ui_coverage_task_rule(task_id, rule_code, rule_version, check_kind)
                VALUES (?, ?, ?, ?)
                """, id, rule.ruleCode(), rule.versionNo(), rule.checkKind());
        return verdict(projectId, id, user);
    }

    public List<Rule> effectiveRules(Long projectId, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        List<Rule> candidates = jdbc.sql("""
                SELECT r.id, r.project_id, r.rule_code, r.version_no, r.title, r.check_kind, r.status
                FROM ui_execution_rule r
                WHERE r.status = 'ACTIVE' AND r.project_id IN (0, :project)
                  AND r.version_no = (SELECT MAX(v.version_no) FROM ui_execution_rule v
                    WHERE v.project_id = r.project_id AND v.rule_code = r.rule_code AND v.status = 'ACTIVE')
                ORDER BY r.project_id DESC, r.rule_code
                """).param("project", projectId).query((rs, row) -> new Rule(rs.getLong(1), rs.getLong(2),
                rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7))).list();
        java.util.LinkedHashMap<String, Rule> byCode = new java.util.LinkedHashMap<>();
        for (Rule candidate : candidates) byCode.putIfAbsent(candidate.ruleCode(), candidate);
        return List.copyOf(byCode.values());
    }

    @Transactional
    public Rule proposeRule(Long projectId, RuleInput input, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        if (input == null || input.ruleCode() == null || !input.ruleCode().matches("[A-Z][A-Z0-9_]{2,63}")
                || !text(input.title(), 255) || !Set.of("FIELD_PAIR", "RUN_ASSERTION").contains(input.checkKind()))
            throw new BusinessException("规则须有编号、名称和支持的检查方式");
        int next = jdbc.sql("SELECT COALESCE(MAX(version_no), 0) + 1 FROM ui_execution_rule WHERE project_id = :project AND rule_code = :code")
                .param("project", projectId).param("code", input.ruleCode()).query(Integer.class).single();
        template.update("""
                INSERT INTO ui_execution_rule(project_id, rule_code, version_no, title, check_kind, status, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, projectId, input.ruleCode(), next, input.title(), input.checkKind(), user.id(), clock.now());
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return ruleById(projectId, id, user);
    }

    public List<Rule> pendingRules(Long projectId, CurrentUser user) {
        access.ensureCanManageProject(projectId, user);
        return jdbc.sql("""
                SELECT id, project_id, rule_code, version_no, title, check_kind, status
                FROM ui_execution_rule WHERE project_id = :project AND status = 'PENDING' ORDER BY id
                """).param("project", projectId).query((rs, row) -> new Rule(rs.getLong(1), rs.getLong(2),
                rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7))).list();
    }

    @Transactional
    public Rule approveRule(Long projectId, Long ruleId, CurrentUser user) {
        access.ensureCanManageProject(projectId, user);
        Rule rule = ruleById(projectId, ruleId, user);
        if (!"PENDING".equals(rule.status())) throw new BusinessException("只能审核待处理规则");
        template.update("UPDATE ui_execution_rule SET status = 'ACTIVE', reviewed_by = ?, reviewed_at = ? WHERE id = ?",
                user.id(), clock.now(), ruleId);
        return ruleById(projectId, ruleId, user);
    }

    private Rule ruleById(Long projectId, Long ruleId, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        return jdbc.sql("""
                SELECT id, project_id, rule_code, version_no, title, check_kind, status
                FROM ui_execution_rule WHERE id = :id AND project_id = :project
                """).param("id", ruleId).param("project", projectId)
                .query((rs, row) -> new Rule(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7)))
                .optional().orElseThrow(() -> new BusinessException("规则不存在"));
    }

    @Transactional
    public Verdict decideRule(Long projectId, Long taskId, String ruleCode,
                              RuleDecision input, CurrentUser user) {
        Task task = task(projectId, taskId, user);
        if (!"UNCONFIRMED".equals(task.inventoryStatus())) throw new BusinessException("已确认的规则范围不能修改；请新建任务");
        if (input == null || !Set.of("APPLICABLE", "NOT_APPLICABLE", "UNKNOWN").contains(input.applicability())
                || !text(input.reason(), 1000)) throw new BusinessException("规则适用性和依据不能为空");
        if ("NOT_APPLICABLE".equals(input.applicability()) && obligations(taskId).stream()
                .anyMatch(item -> ruleCode.equals(item.ruleCode())))
            throw new BusinessException("已有覆盖场景的规则不能标为不适用；请新建任务重新核对");
        int updated = template.update("""
                UPDATE ui_coverage_task_rule SET applicability = ?, reason = ?
                WHERE task_id = ? AND rule_code = ?
                """, input.applicability(), input.reason(), taskId, ruleCode);
        if (updated == 0) throw new BusinessException("该规则不在本轮有效规则快照中");
        return verdict(projectId, taskId, user);
    }

    @Transactional
    public Verdict add(Long projectId, Long taskId, ObligationInput input, CurrentUser user) {
        Task task = task(projectId, taskId, user);
        if (!"UNCONFIRMED".equals(task.inventoryStatus())) throw new BusinessException("已确认的覆盖清单不能由 Agent 修改；请新建任务");
        if (input == null || !key(input.fieldKey()) || !path(input.pagePath())
                || !text(input.fieldLabel(), 255) || !text(input.sourceBasis(), 1000)
                || !Set.of("REQUIRED", "UNKNOWN").contains(input.applicability()))
            throw new BusinessException("规则覆盖项须包含唯一标识、页面、名称、来源和适用状态");
        TaskRule rule = taskRules(taskId).stream().filter(item -> item.ruleCode().equals(input.ruleCode()))
                .findFirst().orElseThrow(() -> new BusinessException("规则不在本轮有效规则快照中"));
        if ("NOT_APPLICABLE".equals(rule.applicability())) throw new BusinessException("不适用规则不能新增覆盖项");
        if (jdbc.sql("SELECT COUNT(*) FROM ui_coverage_obligation WHERE task_id = :id")
                .param("id", taskId).query(Integer.class).single() >= 200)
            throw new BusinessException("单任务最多 200 个字段，请拆分为独立测试任务");
        var existing = jdbc.sql("SELECT id, page_path, field_label, source_basis, applicability FROM ui_coverage_obligation WHERE task_id = :task AND rule_code = :rule AND field_key = :key")
                .param("task", taskId).param("rule", input.ruleCode()).param("key", input.fieldKey())
                .query((rs, row) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)}).list();
        if (!existing.isEmpty()) {
            Object[] old = existing.get(0);
            if (!Objects.equals(old[1], input.pagePath()) || !Objects.equals(old[2], input.fieldLabel())
                    || !Objects.equals(old[3], input.sourceBasis()) || !Objects.equals(old[4], input.applicability()))
                throw new BusinessException("字段标识已用于不同覆盖项");
            return verdict(projectId, taskId, user);
        }
        LocalDateTime now = clock.now();
        template.update("INSERT INTO ui_coverage_obligation(task_id, rule_code, field_key, page_path, field_label, source_basis, applicability, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                taskId, input.ruleCode(), input.fieldKey(), input.pagePath(), input.fieldLabel(), input.sourceBasis(), input.applicability(), now, now);
        return verdict(projectId, taskId, user);
    }

    @Transactional
    public Verdict attach(Long projectId, Long taskId, Long obligationId, EvidenceInput input, CurrentUser user) {
        Task task = task(projectId, taskId, user);
        if (!"CONFIRMED".equals(task.inventoryStatus())) throw new BusinessException("覆盖清单尚未在 Web 确认，不能判定完整覆盖");
        Obligation item = obligations(taskId).stream().filter(o -> o.id().equals(obligationId)).findFirst()
                .orElseThrow(() -> new BusinessException("覆盖项不存在"));
        if (!"REQUIRED".equals(item.applicability())) throw new BusinessException("未知适用性的覆盖项不能提交通过证据");
        TaskRule rule = taskRules(taskId).stream().filter(r -> r.ruleCode().equals(item.ruleCode())).findFirst()
                .orElseThrow(() -> new BusinessException("规则快照不存在"));
        if (!"APPLICABLE".equals(rule.applicability())) throw new BusinessException("规则尚未标为适用");
        if (input == null || input.positiveRunId() == null) throw new BusinessException("规则缺少运行证据");
        if ("FIELD_PAIR".equals(rule.checkKind())) {
            if (input.negativeRunId() == null || input.positiveRunId().equals(input.negativeRunId()))
                throw new BusinessException("必填项需要独立的正向和单项留空运行");
            verifyRun(projectId, task, item, input.positiveRunId(), "POSITIVE", user);
            verifyRun(projectId, task, item, input.negativeRunId(), "NEGATIVE", user);
            verifyPairConsistency(input.positiveRunId(), input.negativeRunId());
        } else {
            if (input.negativeRunId() != null) throw new BusinessException("单运行规则不接受反向运行 ID");
            verifyRun(projectId, task, item, input.positiveRunId(), "RULE", user);
        }
        template.update("UPDATE ui_coverage_obligation SET positive_run_id = ?, negative_run_id = ?, updated_at = ? WHERE id = ?",
                input.positiveRunId(), input.negativeRunId(), clock.now(), obligationId);
        return verdict(projectId, taskId, user);
    }

    @Transactional
    public Verdict confirm(Long projectId, Long taskId, CurrentUser user) {
        Task task = task(projectId, taskId, user);
        if (!Objects.equals(task.createdBy(), user.id()) && !access.isPlatformAdmin(user))
            access.ensureCanManageProject(projectId, user);
        List<Obligation> items = obligations(taskId);
        List<TaskRule> rules = taskRules(taskId);
        if (rules.isEmpty() || rules.stream().anyMatch(r -> "UNKNOWN".equals(r.applicability())
                || "APPLICABLE".equals(r.applicability()) && items.stream().noneMatch(o -> o.ruleCode().equals(r.ruleCode())))
                || items.stream().anyMatch(o -> "UNKNOWN".equals(o.applicability())))
            throw new BusinessException("清单为空或存在未确认字段，不能确认范围");
        template.update("UPDATE ui_coverage_task SET inventory_status = 'CONFIRMED', confirmed_by = ?, confirmed_at = ? WHERE id = ?",
                user.id(), clock.now(), task.id());
        return verdict(projectId, taskId, user);
    }

    public Verdict verdict(Long projectId, Long taskId, CurrentUser user) {
        Task task = task(projectId, taskId, user);
        List<Obligation> items = obligations(taskId);
        List<TaskRule> rules = taskRules(taskId);
        int required = (int) items.stream().filter(o -> "REQUIRED".equals(o.applicability())).count();
        int covered = (int) items.stream().filter(o -> "COVERED".equals(o.status())).count();
        String result = assess(task.inventoryStatus(), rules, items);
        return new Verdict(task.id(), task.taskKey(), task.targetOrigin(), task.inventoryStatus(), result,
                required, covered, rules, items);
    }

    static String assess(String inventoryStatus, List<TaskRule> rules, List<Obligation> items) {
        if (!"CONFIRMED".equals(inventoryStatus)) return "INVENTORY_UNCONFIRMED";
        if (rules.isEmpty() || rules.stream().noneMatch(r -> "APPLICABLE".equals(r.applicability()))
                || items.isEmpty() || rules.stream().anyMatch(r ->
                !"COVERED".equals(r.status()) && !"NOT_APPLICABLE".equals(r.applicability()))
                || items.stream().anyMatch(item -> !"COVERED".equals(item.status())
                    || "UNKNOWN".equals(item.applicability()))) return "INCOMPLETE";
        return "PASS";
    }

    public List<Verdict> list(Long projectId, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        String sql = access.isPlatformAdmin(user)
                ? "SELECT id FROM ui_coverage_task WHERE project_id = :project ORDER BY id DESC LIMIT 100"
                : """
                  SELECT t.id FROM ui_coverage_task t WHERE t.project_id = :project
                    AND (t.created_by = :user OR EXISTS (
                      SELECT 1 FROM project_member pm WHERE pm.project_id = t.project_id
                      AND pm.user_id = :user AND pm.project_role = 'OWNER'))
                  ORDER BY t.id DESC LIMIT 100
                  """;
        return jdbc.sql(sql).param("project", projectId).param("user", user.id()).query(Long.class).list()
                .stream().map(id -> verdict(projectId, id, user)).toList();
    }

    private void verifyRun(Long projectId, Task task, Obligation item, Long runId, String role, CurrentUser user) {
        var rows = jdbc.sql("""
                SELECT r.result, r.created_by, a.project_id, a.asset_type, v.mapping_json,
                       p.target_host, r.created_at, v.review_status, a.status
                FROM automation_run r JOIN automation_asset a ON a.id = r.asset_id
                JOIN automation_asset_version v ON v.asset_id = r.asset_id AND v.version_no = r.version_no
                JOIN dsh_capture_run c ON c.session_id = r.evidence_session_id
                JOIN browser_profile p ON p.id = c.profile_id
                WHERE r.id = :id
                """).param("id", runId).query((rs, row) -> new Object[] {
                rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getTimestamp(7).toLocalDateTime(), rs.getString(8), rs.getString(9)}).list();
        if (rows.isEmpty()) throw new BusinessException("运行记录不存在");
        Object[] run = rows.get(0);
        if (!"PASS".equals(run[0]) || !Objects.equals(user.id(), run[1])
                || !Objects.equals(projectId, run[2]) || !"UI".equals(run[3])
                || !Objects.equals(task.targetOrigin(), run[5])
                || ((LocalDateTime) run[6]).isBefore(task.createdAt())
                || !"APPROVED".equals(run[7]) || "ARCHIVED".equals(run[8]))
            throw new BusinessException("覆盖证据必须是本人在本项目完成且映射已审核的 UI PASS 运行");
        JsonNode mapping;
        try { mapping = mapper.readTree((String) run[4]); }
        catch (Exception ex) { throw new BusinessException("执行映射无法读取"); }
        if ("RULE".equals(role)) {
            JsonNode proof = mapping.path("ruleCoverage");
            int assertionIndex = proof.path("assertionIndex").asInt(-1);
            if (!item.ruleCode().equals(proof.path("ruleCode").asText())
                    || !item.fieldKey().equals(proof.path("subjectKey").asText())
                    || !item.pagePath().equals(mapping.path("page").asText())
                    || assertionIndex < 0 || assertionIndex >= mapping.path("assertions").size()
                    || "hidden".equals(mapping.path("assertions").get(assertionIndex).path("state").asText()))
                throw new BusinessException("运行映射未声明该规则的可观察断言");
            return;
        }
        JsonNode proof = mapping.path("uiCoverage");
        if (!item.ruleCode().equals(proof.path("ruleCode").asText())
                || !item.fieldKey().equals(proof.path("fieldKey").asText()) || !role.equals(proof.path("role").asText())
                || !item.pagePath().equals(mapping.path("page").asText()))
            throw new BusinessException("运行映射未声明对应字段与正反场景");
        JsonNode steps = mapping.path("steps");
        JsonNode assertions = mapping.path("assertions");
        int fillIndex = proof.path("fieldStepIndex").asInt(-1);
        int submitIndex = proof.path("submitStepIndex").asInt(-1);
        if (fillIndex < 0 || fillIndex >= steps.size() || submitIndex <= fillIndex || submitIndex >= steps.size()
                || !Set.of("fill", "select", "check", "uncheck").contains(steps.get(fillIndex).path("type").asText())
                || !"click".equals(steps.get(submitIndex).path("type").asText()))
            throw new BusinessException("覆盖映射缺少字段输入与提交动作");
        JsonNode data = steps.get(fillIndex).path("data");
        if ("NEGATIVE".equals(role)) {
            if (!"fill".equals(steps.get(fillIndex).path("type").asText()) || !data.isTextual() || !data.asText().isEmpty())
                throw new BusinessException("单项留空场景必须将目标字段明确清空");
            int errorIndex = proof.path("errorAssertionIndex").asInt(-1);
            int noSuccessIndex = proof.path("noSuccessAssertionIndex").asInt(-1);
            if (errorIndex < 0 || errorIndex >= assertions.size() || noSuccessIndex < 0 || noSuccessIndex >= assertions.size()
                    || !"hidden".equals(assertions.get(noSuccessIndex).path("state").asText())
                    || !"visible".equals(assertions.get(errorIndex).path("state").asText("visible")))
                throw new BusinessException("单项留空场景需要错误提示和无成功结果两项断言");
        } else if (!data.isTextual() || data.asText().isBlank() || assertions.isEmpty()) {
            throw new BusinessException("正向场景缺少有效输入或成功断言");
        }
    }

    private void verifyPairConsistency(Long positiveId, Long negativeId) {
        JsonNode positive = runMapping(positiveId);
        JsonNode negative = runMapping(negativeId);
        JsonNode positiveProof = positive.path("uiCoverage");
        JsonNode negativeProof = negative.path("uiCoverage");
        int fieldIndex = positiveProof.path("fieldStepIndex").asInt(-1);
        int submitIndex = positiveProof.path("submitStepIndex").asInt(-1);
        if (fieldIndex != negativeProof.path("fieldStepIndex").asInt(-2)
                || submitIndex != negativeProof.path("submitStepIndex").asInt(-2)
                || !positive.path("page").equals(negative.path("page")))
            throw new BusinessException("正向与单项留空场景须使用相同页面和步骤位置");
        JsonNode a = positive.path("steps");
        JsonNode b = negative.path("steps");
        if (submitIndex >= a.size() || submitIndex >= b.size())
            throw new BusinessException("正反场景提交步骤不一致");
        for (int index = 0; index <= submitIndex; index++) {
            JsonNode left = a.get(index);
            JsonNode right = b.get(index);
            if (index == fieldIndex) {
                ObjectNode withoutData = ((ObjectNode) left).deepCopy();
                withoutData.remove("data");
                ObjectNode other = ((ObjectNode) right).deepCopy();
                other.remove("data");
                if (!withoutData.equals(other)) throw new BusinessException("正反场景除目标字段外的动作必须一致");
            } else if (!left.equals(right)) {
                throw new BusinessException("正反场景除目标字段外的动作必须一致");
            }
        }
    }

    private JsonNode runMapping(Long runId) {
        String json = jdbc.sql("""
                SELECT v.mapping_json FROM automation_run r JOIN automation_asset_version v
                  ON v.asset_id = r.asset_id AND v.version_no = r.version_no WHERE r.id = :id
                """).param("id", runId).query(String.class).single();
        try { return mapper.readTree(json); }
        catch (Exception ex) { throw new BusinessException("执行映射无法读取"); }
    }

    private Task task(Long projectId, Long taskId, CurrentUser user) {
        access.ensureCanAccess(projectId, user);
        var rows = jdbc.sql("SELECT id, task_key, target_origin, inventory_status, created_by, created_at FROM ui_coverage_task WHERE id = :id AND project_id = :project")
                .param("id", taskId).param("project", projectId)
                .query((rs, row) -> new Task(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getTimestamp(6).toLocalDateTime())).list();
        if (rows.isEmpty())
            throw new BusinessException("覆盖任务不存在或无权访问");
        if (!Objects.equals(rows.get(0).createdBy(), user.id()) && !access.isPlatformAdmin(user))
            access.ensureCanManageProject(projectId, user);
        return rows.get(0);
    }

    private List<Obligation> obligations(Long taskId) {
        return obligationsWithoutRules(taskId);
    }

    private List<TaskRule> taskRules(Long taskId) {
        List<Obligation> items = obligationsWithoutRules(taskId);
        return jdbc.sql("SELECT rule_code, rule_version, check_kind, applicability, reason FROM ui_coverage_task_rule WHERE task_id = :id ORDER BY rule_code")
                .param("id", taskId).query((rs, row) -> {
                    String code = rs.getString(1);
                    String applicability = rs.getString(4);
                    List<Obligation> matches = items.stream().filter(item -> code.equals(item.ruleCode())).toList();
                    String status = "NOT_APPLICABLE".equals(applicability) ? "NOT_APPLICABLE"
                            : "UNKNOWN".equals(applicability) ? "UNKNOWN"
                            : matches.isEmpty() || matches.stream().anyMatch(item -> !"COVERED".equals(item.status()))
                            ? "PENDING" : "COVERED";
                    return new TaskRule(code, rs.getInt(2), rs.getString(3), applicability, rs.getString(5), status);
                }).list();
    }

    private List<Obligation> obligationsWithoutRules(Long taskId) {
        return jdbc.sql("""
                SELECT o.id, o.rule_code, o.field_key, o.page_path, o.field_label,
                       o.source_basis, o.applicability, o.positive_run_id, o.negative_run_id, r.check_kind
                FROM ui_coverage_obligation o JOIN ui_coverage_task_rule r
                  ON r.task_id = o.task_id AND r.rule_code = o.rule_code
                WHERE o.task_id = :id ORDER BY o.id
                """).param("id", taskId).query((rs, row) -> {
                    Long positive = rs.getObject(8, Long.class);
                    Long negative = rs.getObject(9, Long.class);
                    String kind = rs.getString(10);
                    return new Obligation(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), positive, negative,
                            positive != null && ("RUN_ASSERTION".equals(kind) || negative != null) ? "COVERED" : "PENDING");
                }).list();
    }

    private static boolean key(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"); }
    private static boolean path(String value) { return value != null && value.startsWith("/") && !value.startsWith("//") && value.length() <= 512; }
    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private record Task(Long id, String taskKey, String targetOrigin, String inventoryStatus,
                        Long createdBy, LocalDateTime createdAt) {}
}

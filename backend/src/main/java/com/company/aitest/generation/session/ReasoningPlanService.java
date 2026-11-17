package com.company.aitest.generation.session;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A deterministic, auditable dispatcher for generic testing methods and quality obligations. */
@Service
public class ReasoningPlanService {
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;
    public ReasoningPlanService(JdbcTemplate jdbcTemplate, TimeProvider timeProvider, ObjectMapper objectMapper) { this.jdbcTemplate = jdbcTemplate; this.timeProvider = timeProvider; this.objectMapper = objectMapper; }

    public record PlanView(Long id, int version, List<Map<String, Object>> items, Map<String, Object> summary) {}

    @Transactional
    public PlanView createOrReplace(Long projectId, Long sessionId, RequirementAnalysisRecord analysis,
                                    Long contractId, CurrentUser user) {
        List<Map<String, Object>> points = list(analysis.testPoints());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> point : points) {
            if ("EXCLUDED".equalsIgnoreCase(text(point.get("generation_scope")))) continue;
            String pointType = text(point.get("point_type")).toUpperCase(Locale.ROOT);
            List<String> skills = skills(pointType, text(point.get("description")) + " " + text(point.get("title")));
            SkillRules rules = activeSkillRules(skills);
            List<String> methods = rules.methods().isEmpty() ? methods(skills) : rules.methods();
            List<String> obligations = rules.obligations().isEmpty() ? obligations(skills) : rules.obligations();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("test_point_ref", text(point.get("id"))); item.put("title", first(text(point.get("title")), text(point.get("description"))));
            item.put("point_type", pointType); item.put("skills", skills); item.put("skill_versions", rules.versions()); item.put("methods", methods); item.put("coverage_obligations", obligations);
            item.put("quality_rules", rules.qualityRules().isEmpty()
                    ? List.of("一条用例只验证一个明确目标", "步骤和预期必须可重复执行", "未知事实标记为待确认")
                    : rules.qualityRules());
            items.add(item);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        int version = nextVersion(analysis.id());
        summary.put("plan_version", version); summary.put("point_count", items.size());
        summary.put("skill_count", items.stream().flatMap(item -> ((List<String>) item.get("skills")).stream()).distinct().count());
        summary.put("rule", "推理 Skill 只决定测试方法与质量规则，不提供业务事实；业务事实来自冻结 Context Contract。");
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("UPDATE reasoning_plan SET status = 'SUPERSEDED', updated_at = ? WHERE analysis_id = ? AND status = 'ACTIVE'", now, analysis.id());
        jdbcTemplate.update("""
                INSERT INTO reasoning_plan(project_id, session_id, analysis_id, context_contract_id, version, status,
                    plan_json, summary_json, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)
                """, projectId, sessionId, analysis.id(), contractId, version, json(items), json(summary), user == null ? 0L : user.id(), now, now);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return new PlanView(id, version, items, summary);
    }
    private int nextVersion(Long analysisId) {
        Integer current = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(version), 0) FROM reasoning_plan WHERE analysis_id = ?", Integer.class, analysisId);
        return (current == null ? 0 : current) + 1;
    }
    /** Dispatcher identifies generic requirement characteristics; active DB Skill versions own method/rule content. */
    private SkillRules activeSkillRules(List<String> keys) {
        if (keys.isEmpty()) return new SkillRules(List.of(), List.of(), List.of(), Map.of());
        String placeholders = String.join(",", java.util.Collections.nCopies(keys.size(), "?"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT skill_key, version, method_rules_json, quality_rules_json FROM reasoning_skill WHERE status = 'ACTIVE' AND skill_key IN (" + placeholders + ")", keys.toArray());
        List<String> methods = new ArrayList<>(), obligations = new ArrayList<>(), qualityRules = new ArrayList<>();
        Map<String, Integer> versions = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = text(row.get("skill_key"));
            versions.put(key, ((Number) row.get("version")).intValue());
            Map<String, Object> methodRule = object(text(row.get("method_rules_json")));
            methods.addAll(strings(methodRule.get("methods"))); obligations.addAll(strings(methodRule.get("obligations")));
            Map<String, Object> qualityRule = object(text(row.get("quality_rules_json")));
            qualityRules.addAll(strings(qualityRule.get("rules")));
        }
        return new SkillRules(distinct(methods), distinct(obligations), distinct(qualityRules), versions);
    }
    private List<String> strings(Object value) { if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList(); return List.of(); }
    private List<String> distinct(List<String> values) { return new ArrayList<>(new java.util.LinkedHashSet<>(values)); }
    private record SkillRules(List<String> methods, List<String> obligations, List<String> qualityRules, Map<String, Integer> versions) {}

    public PlanView findByAnalysis(Long analysisId) {
        if (analysisId == null) return null;
        List<PlanView> rows = jdbcTemplate.query("SELECT id, version, plan_json, summary_json FROM reasoning_plan WHERE analysis_id = ? AND status = 'ACTIVE' ORDER BY id DESC LIMIT 1",
                (rs, i) -> new PlanView(rs.getLong("id"), rs.getInt("version"), list(rs.getString("plan_json")), object(rs.getString("summary_json"))), analysisId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private List<String> skills(String type, String text) { String content = (type + " " + text).toUpperCase(Locale.ROOT); List<String> r = new ArrayList<>(); if (content.contains("BOUNDARY") || content.contains("FIELD") || content.contains("输入") || content.contains("格式")) r.add("FIELD_BOUNDARY"); if (content.contains("STATE") || content.contains("FLOW") || content.contains("流程") || content.contains("审批")) r.add("STATE_FLOW"); if (content.contains("BRANCH") || content.contains("RULE") || content.contains("规则") || content.contains("组合")) r.add("RULE_COMBINATION"); if (content.contains("AUTH") || content.contains("DATA") || content.contains("CONCURRENCY") || content.contains("IDEMPOTENT") || content.contains("权限") || content.contains("同步")) r.add("ACCESS_INTEGRATION"); if (r.isEmpty()) r.add("STATE_FLOW"); return r; }
    private List<String> methods(List<String> skills) { List<String> r = new ArrayList<>(); for (String skill : skills) { if (skill.equals("FIELD_BOUNDARY")) { r.add("等价类划分法"); r.add("边界值分析法"); } else if (skill.equals("RULE_COMBINATION")) r.add("判定表法"); else if (skill.equals("ACCESS_INTEGRATION")) r.add("权限矩阵"); else r.add("场景法"); } return r.stream().distinct().toList(); }
    private List<String> obligations(List<String> skills) { List<String> r = new ArrayList<>(); for (String skill : skills) { if (skill.equals("FIELD_BOUNDARY")) r.addAll(List.of("有效/无效等价类", "边界值")); else if (skill.equals("RULE_COMBINATION")) r.addAll(List.of("条件组合", "规则命中与不命中")); else if (skill.equals("ACCESS_INTEGRATION")) r.addAll(List.of("权限或数据隔离", "失败/重复行为")); else r.addAll(List.of("主路径", "异常或状态迁移")); } return r.stream().distinct().toList(); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private String first(String a, String b) { return a == null || a.isBlank() ? b : a; }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception e) { return "[]"; } }
    private Map<String,Object> object(String value) { try { return value == null || value.isBlank() ? Map.of() : objectMapper.readValue(value, new TypeReference<>() {}); } catch (Exception ignored) { return Map.of(); } }
    private List<Map<String,Object>> list(String value) { try { return value == null || value.isBlank() ? List.of() : objectMapper.readValue(value, new TypeReference<>() {}); } catch (Exception ignored) { return List.of(); } }
}

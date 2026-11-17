package com.company.aitest.generation.session;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.semantic.ProjectSemanticContextService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Freezes the usable facts, background and evidence for one confirmed requirement scope.
 * Downstream nodes consume this snapshot rather than recalling a different semantic context.
 */
@Service
public class ContextContractService {
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;

    public ContextContractService(JdbcTemplate jdbcTemplate, TimeProvider timeProvider, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        this.objectMapper = objectMapper;
    }

    public record ContractView(Long id, int version, String status, Map<String, Object> facts,
                               List<Map<String, Object>> background, List<Map<String, Object>> evidence,
                               List<Map<String, Object>> conflicts, String promptSection) {}

    @Transactional
    public ContractView freeze(Long projectId, Long sessionId, RequirementAnalysisRecord analysis,
                               Long modelConfigId, ProjectSemanticContextService.BuildResult semantic,
                               CurrentUser user) {
        ContractView existing = findByAnalysis(analysis.id());
        if (existing != null) return existing;
        Map<String, Object> root = object(analysis.analysisResult());
        Map<String, Object> facts = new LinkedHashMap<>();
        if (root != null) {
            for (String key : List.of("requirement_understanding", "business_domain", "requirement_type",
                    "requirement_atoms", "test_units", "clarification_questions", "uncertain_items")) {
                if (root.containsKey(key)) facts.put(key, root.get(key));
            }
        }
        List<Map<String, Object>> confirmedAnswers = list(analysis.clarificationAnswers());
        if (!confirmedAnswers.isEmpty()) facts.put("clarification_answers", confirmedAnswers);
        List<Map<String, Object>> background = new ArrayList<>();
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (ProjectSemanticContextService.SemanticSignal signal : semantic == null ? List.<ProjectSemanticContextService.SemanticSignal>of() : semantic.signals()) {
            Map<String, Object> item = signalToMap(signal);
            if (signal.category() != null && (signal.category().startsWith("TOM:") || signal.category().startsWith("页面:"))) {
                evidence.add(item);
            } else {
                background.add(item);
            }
        }
        List<Map<String, Object>> conflicts = root == null ? List.of() : list(root.get("conflicts"));
        String promptSection = semantic == null ? "" : safe(semantic.promptSection());
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO context_contract(project_id, session_id, analysis_id, version, status,
                    requirement_snapshot, facts_json, background_json, evidence_json, conflict_json,
                    prompt_section, model_config_id, created_by, frozen_at, created_at, updated_at)
                VALUES (?, ?, ?, 1, 'FROZEN', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, projectId, sessionId, analysis.id(), safe(analysis.requirementText()), json(facts),
                json(background), json(evidence), json(conflicts), promptSection, modelConfigId,
                user == null || user.id() == null ? 0L : user.id(), now, now, now);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        for (Map<String, Object> item : background) {
            jdbcTemplate.update("""
                    INSERT INTO context_contract_item(contract_id, layer_name, source_type, source_ref_id,
                        source_version, asset_key, title, content_excerpt, evidence_level, priority_rank,
                        included, conflict_status, created_at)
                    VALUES (?, ?, ?, NULL, NULL, ?, ?, ?, ?, ?, 1, 'NONE', ?)
                    """, id, "BACKGROUND", safeText(item.get("category")),
                    key(item), safeText(item.get("title")), clip(safeText(item.get("summary")), 2000),
                    "BACKGROUND", 0, now);
        }
        for (Map<String, Object> item : evidence) {
            jdbcTemplate.update("""
                    INSERT INTO context_contract_item(contract_id, layer_name, source_type, source_ref_id,
                        source_version, asset_key, title, content_excerpt, evidence_level, priority_rank,
                        included, conflict_status, created_at)
                    VALUES (?, ?, ?, NULL, NULL, ?, ?, ?, ?, ?, 1, 'NONE', ?)
                    """, id, "EVIDENCE", safeText(item.get("category")),
                    key(item), safeText(item.get("title")), clip(safeText(item.get("summary")), 2000),
                    "FACT_REFERENCE", 0, now);
        }
        return new ContractView(id, 1, "FROZEN", facts, background, evidence, conflicts, promptSection);
    }

    public ContractView findByAnalysis(Long analysisId) {
        if (analysisId == null) return null;
        List<ContractView> rows = jdbcTemplate.query("""
                SELECT id, version, status, facts_json, background_json, evidence_json, conflict_json, prompt_section
                FROM context_contract WHERE analysis_id = ? AND status = 'FROZEN'
                ORDER BY version DESC, id DESC LIMIT 1
                """, (rs, rowNum) -> new ContractView(rs.getLong("id"), rs.getInt("version"), rs.getString("status"),
                object(rs.getString("facts_json")), list(rs.getString("background_json")),
                list(rs.getString("evidence_json")), list(rs.getString("conflict_json")), rs.getString("prompt_section")), analysisId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ProjectSemanticContextService.BuildResult semanticContext(ContractView contract) {
        if (contract == null) return new ProjectSemanticContextService.BuildResult("", List.of());
        List<ProjectSemanticContextService.SemanticSignal> signals = new ArrayList<>();
        for (Map<String, Object> item : combined(contract.background(), contract.evidence())) {
            signals.add(new ProjectSemanticContextService.SemanticSignal(safeText(item.get("category")),
                    safeText(item.get("title")), safeText(item.get("summary")), safeText(item.get("routeHint")),
                    number(item.get("scoreBoost")), null));
        }
        return new ProjectSemanticContextService.BuildResult(safe(contract.promptSection()), signals);
    }

    public Map<String, Object> summary(ContractView contract) {
        if (contract == null) return Map.of();
        return Map.of("id", contract.id(), "version", contract.version(), "status", contract.status(),
                "facts_count", contract.facts().size(), "background_count", contract.background().size(),
                "evidence_count", contract.evidence().size(), "conflict_count", contract.conflicts().size());
    }

    private Map<String, Object> signalToMap(ProjectSemanticContextService.SemanticSignal signal) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("category", safe(signal.category())); result.put("title", safe(signal.title()));
        result.put("summary", safe(signal.summary())); result.put("routeHint", safe(signal.routeHint()));
        result.put("scoreBoost", signal.scoreBoost());
        return result;
    }
    private List<Map<String, Object>> combined(List<Map<String, Object>> a, List<Map<String, Object>> b) { List<Map<String, Object>> r = new ArrayList<>(); r.addAll(a); r.addAll(b); return r; }
    private String key(Map<String, Object> item) { return safeText(item.get("category")) + ":" + safeText(item.get("title")); }
    private Map<String, Object> object(String value) { try { return value == null || value.isBlank() ? new LinkedHashMap<>() : objectMapper.readValue(value, new TypeReference<>() {}); } catch (Exception ignored) { return new LinkedHashMap<>(); } }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> list(Object value) { try { if (value instanceof String text) return text.isBlank() ? List.of() : objectMapper.readValue(text, new TypeReference<>() {}); if (value instanceof List<?> list) return (List<Map<String, Object>>) list; } catch (Exception ignored) {} return List.of(); }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception e) { return "{}"; } }
    private String safe(String value) { return value == null ? "" : value; }
    private String safeText(Object value) { return value == null ? "" : String.valueOf(value); }
    private String clip(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
    private double number(Object value) { return value instanceof Number n ? n.doubleValue() : 0D; }
}

package com.company.aitest.minitom;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.BusinessException;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.knowledge.KnowledgeDepositionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts only submitted, non-deprecated formal cases into reviewable TOM candidates.
 * It deliberately uses deterministic structural extraction: a historic case is evidence, never an inferred fact.
 */
@Service
public class HistoricalCaseTomCandidateService {
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;
    private final KnowledgeDepositionService knowledgeDepositionService;

    public HistoricalCaseTomCandidateService(JdbcTemplate jdbcTemplate, TimeProvider timeProvider,
                                             ObjectMapper objectMapper, KnowledgeDepositionService knowledgeDepositionService) {
        this.jdbcTemplate = jdbcTemplate; this.timeProvider = timeProvider;
        this.objectMapper = objectMapper; this.knowledgeDepositionService = knowledgeDepositionService;
    }

    public record ExtractionResult(int candidates, int relations, int unchanged, int skipped) {}
    public record RelationCandidateView(Long id, Long projectId, Long fromModelId, String fromName,
                                        String relationType, Long toModelId, String toName,
                                        String sourceType, Long sourceRefId, String sourceRefsJson,
                                        String status, boolean requiresHumanConfirm, java.math.BigDecimal confidence,
                                        LocalDateTime createdAt) {}
    public record AliasCandidateView(Long id, Long tomId, String tomName, String aliasName, String sourceType,
                                     Long sourceRefId, String status, LocalDateTime createdAt) {}
    public record ImpactCandidateView(Long id, Long fromModelId, String fromName, String targetKind,
                                      String targetRef, String impactType, Long sourceRefId,
                                      String sourceType, String status, LocalDateTime createdAt) {}

    public record ManualRelationCommand(Long fromModelId, String relationType, Long toModelId, String evidenceText) {}

    /** A human can propose a relation between existing confirmed objects; it remains pending review. */
    @Transactional
    public RelationCandidateView proposeManualRelation(Long projectId, ManualRelationCommand command, CurrentUser user) {
        if (command == null || command.fromModelId() == null || command.toModelId() == null
                || command.fromModelId().equals(command.toModelId())) throw new BusinessException("请选择两个不同的 TOM 对象");
        String type = command.relationType();
        String expected = TomRelationTypes.expectedPair(type);
        if (expected == null) {
            throw new BusinessException("TOM 关系类型无效");
        }
        String evidence = safe(command.evidenceText()).strip();
        if (evidence.length() < 10 || evidence.length() > 1000) throw new BusinessException("关系依据须为 10 至 1000 字");
        List<Map<String, Object>> endpoints = jdbcTemplate.queryForList("""
                SELECT id, model_type FROM test_object_model
                WHERE project_id = ? AND status = 'ACTIVE' AND id IN (?, ?)
                """, projectId, command.fromModelId(), command.toModelId());
        if (endpoints.size() != 2) throw new BusinessException("关系两端必须是同一来源项目中已确认的 TOM 对象");
        Map<Long, String> types = new LinkedHashMap<>();
        for (Map<String, Object> row : endpoints) types.put(((Number) row.get("id")).longValue(), (String) row.get("model_type"));
        if (!expected.equals(types.get(command.fromModelId()) + ":" + types.get(command.toModelId()))) {
            throw new BusinessException("关系类型与两端 TOM 对象类型不匹配");
        }
        Integer duplicate = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM test_object_model_relation
                WHERE project_id = ? AND from_model_id = ? AND to_model_id = ? AND relation_type = ?
                  AND status IN ('CANDIDATE', 'CONFIRMED', 'ACTIVE')
                """, Integer.class, projectId, command.fromModelId(), command.toModelId(), type);
        if (duplicate != null && duplicate > 0) throw new BusinessException("该关系已经存在或正在审核");
        LocalDateTime now = timeProvider.now();
        String key = "MANUAL_REL:" + projectId + ":" + command.fromModelId() + ":" + type + ":" + command.toModelId();
        jdbcTemplate.update("""
                INSERT INTO test_object_model_relation(project_id, from_model_id, relation_type, to_model_id,
                    confidence, status, source_type, source_refs_json, candidate_key, requires_human_confirm,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 1.00, 'CANDIDATE', 'MANUAL_SECTION', ?, ?, 1, ?, ?)
                """, projectId, command.fromModelId(), type, command.toModelId(),
                json(Map.of("proposedBy", user.id(), "evidenceText", evidence)), key, now, now);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("""
                INSERT INTO tom_evidence(project_id, relation_id, source_type, source_ref_type, source_version,
                    source_excerpt, evidence_level, conflict_status, review_status, candidate_key, created_at)
                VALUES (?, ?, 'MANUAL_SECTION', 'HUMAN_RELATION', 1, ?, 'DIRECT', 'NONE', 'PENDING', ?, ?)
                """, projectId, id, evidence, key, now);
        return relation(projectId, id);
    }

    @Transactional
    public ExtractionResult extractProject(Long projectId, CurrentUser user) {
        List<Long> ids = jdbcTemplate.queryForList("""
                SELECT id FROM test_case_asset
                WHERE project_id = ? AND case_scope = 'PROJECT' AND case_status = 'SUBMITTED'
                  AND deprecated_at IS NULL AND import_source_type IS NULL
                ORDER BY id
                """, Long.class, projectId);
        ExtractionResult total = new ExtractionResult(0, 0, 0, 0);
        for (Long id : ids) total = plus(total, extractOne(projectId, id, user));
        return total;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExtractionResult extractOne(Long projectId, Long caseId, CurrentUser user) {
        List<CaseRow> rows = jdbcTemplate.query("""
                SELECT id, project_id, case_title, module_name, precondition, steps, expected_result,
                    priority, case_type, scenario_type, design_method, source_refs_json
                FROM test_case_asset
                WHERE id = ? AND project_id = ? AND case_scope = 'PROJECT' AND case_status = 'SUBMITTED'
                  AND deprecated_at IS NULL
                """, (rs, i) -> new CaseRow(rs.getLong("id"), rs.getLong("project_id"), rs.getString("case_title"),
                rs.getString("module_name"), rs.getString("precondition"), rs.getString("steps"),
                rs.getString("expected_result"), rs.getString("priority"), rs.getString("case_type"),
                rs.getString("scenario_type"), rs.getString("design_method"), rs.getString("source_refs_json")), caseId, projectId);
        if (rows.isEmpty()) return new ExtractionResult(0, 0, 0, 1);
        return extractRow(rows.get(0), user);
    }

    /** Use the import transaction so the new evidence row is visible before it commits. */
    @Transactional
    public ExtractionResult extractImportedItem(Long projectId, Long itemId, CurrentUser user) {
        List<CaseRow> rows = jdbcTemplate.query("""
                SELECT id, project_id, case_title, module_name, precondition, steps, expected_result,
                    priority, case_type, NULL AS scenario_type, design_method, NULL AS source_refs_json
                FROM historical_case_import_item
                WHERE id = ? AND project_id = ? AND eligibility_status = 'ELIGIBLE'
                """, (rs, i) -> new CaseRow(rs.getLong("id"), rs.getLong("project_id"), rs.getString("case_title"),
                rs.getString("module_name"), rs.getString("precondition"), rs.getString("steps"),
                rs.getString("expected_result"), rs.getString("priority"), rs.getString("case_type"),
                rs.getString("scenario_type"), rs.getString("design_method"), rs.getString("source_refs_json"),
                "IMPORTED_HISTORICAL_CASE", "HISTORICAL_CASE_IMPORT_ITEM"), itemId, projectId);
        if (rows.isEmpty()) return new ExtractionResult(0, 0, 0, 1);
        return extractRow(rows.get(0), user);
    }

    private ExtractionResult extractRow(CaseRow row, CurrentUser user) {
        if (!isEligibleForExtraction(row.caseTitle(), row.moduleName(), row.precondition(), row.steps(), row.expectedResult())) {
            return new ExtractionResult(0, 0, 0, 1);
        }
        Long projectId = row.projectId();
        String sourceHash = sha256(join(row.moduleName(), row.caseTitle(), row.precondition(), row.steps(), row.expectedResult(), row.sourceRefsJson()));
        LocalDateTime now = timeProvider.now();
        long actor = user == null || user.id() == null ? 0L : user.id();
        Candidate module = create(projectId, "MODULE", row.moduleName(), "历史正式用例所属模块", row, sourceHash, actor, now);
        Candidate action = create(projectId, "ACTION", row.caseTitle(), "历史正式用例验证的业务行为：" + safe(row.caseTitle()), row, sourceHash, actor, now);
        Candidate flow = blank(row.precondition()) && blank(row.steps()) ? null : create(projectId, "FLOW", clip(first(row.caseTitle(), row.moduleName()), 256),
                "前置条件：" + safe(row.precondition()) + "\n步骤：" + safe(row.steps()), row, sourceHash, actor, now);
        Candidate assertion = blank(row.expectedResult()) ? null : create(projectId, "ASSERTION", clip("断言：" + first(row.caseTitle(), "历史用例"), 256),
                row.expectedResult(), row, sourceHash, actor, now);
        Candidate stateHint = extractState(row.expectedResult());
        Candidate state = stateHint == null ? null : create(projectId, "STATE", stateHint.name(),
                "历史正式用例断言中的状态变化：" + stateHint.name(), row, sourceHash, actor, now);
        int candidates = count(module, action, flow, assertion, state, true);
        int unchanged = count(module, action, flow, assertion, state, false);
        int relations = 0;
        relations += relation(projectId, module, action, "MODULE_HAS_ACTION", row, sourceHash, actor, now) ? 1 : 0;
        relations += relation(projectId, action, flow, "ACTION_HAS_FLOW", row, sourceHash, actor, now) ? 1 : 0;
        relations += relation(projectId, action, assertion, "ACTION_HAS_ASSERTION", row, sourceHash, actor, now) ? 1 : 0;
        relations += relation(projectId, action, state, "ACTION_CHANGES_STATE", row, sourceHash, actor, now) ? 1 : 0;
        insertExplicitAliases(projectId, action, row, sourceHash, now);
        impact(projectId, action, "MODULE", row.moduleName(), "IMPACTS_MODULE", row, sourceHash, now);
        impact(projectId, action, "FLOW", flow == null ? null : flow.name(), "IMPACTS_FLOW", row, sourceHash, now);
        impact(projectId, action, "ASSERTION", assertion == null ? null : assertion.name(), "IMPACTS_ASSERTION", row, sourceHash, now);
        if (knowledgeDepositionService != null) {
            if ("IMPORTED_HISTORICAL_CASE".equals(row.sourceType())) {
                knowledgeDepositionService.depositImportedHistoricalTestCase(projectId, row.id(), row.caseTitle(), row.moduleName(),
                        row.precondition(), row.steps(), row.expectedResult(), actor);
            } else {
                knowledgeDepositionService.depositFormalTestCase(projectId, row.id(), row.caseTitle(),
                        row.moduleName(), row.precondition(), row.steps(), row.expectedResult(), actor);
            }
        }
        return new ExtractionResult(candidates, relations, unchanged, 0);
    }

    public List<RelationCandidateView> listRelationCandidates(Long projectId) {
        return jdbcTemplate.query("""
                SELECT r.id, r.project_id, r.from_model_id, f.name AS from_name, r.relation_type,
                       r.to_model_id, t.name AS to_name, r.source_type, r.source_ref_id,
                       r.source_refs_json, r.status, r.requires_human_confirm, r.confidence, r.created_at
                FROM test_object_model_relation r
                JOIN test_object_model f ON f.id = r.from_model_id
                JOIN test_object_model t ON t.id = r.to_model_id
                WHERE r.project_id = ? AND r.status = 'CANDIDATE'
                ORDER BY r.created_at DESC, r.id DESC
                """, (rs, i) -> new RelationCandidateView(rs.getLong("id"), rs.getLong("project_id"),
                rs.getLong("from_model_id"), rs.getString("from_name"), rs.getString("relation_type"),
                rs.getLong("to_model_id"), rs.getString("to_name"), rs.getString("source_type"),
                rs.getObject("source_ref_id") == null ? null : rs.getLong("source_ref_id"),
                rs.getString("source_refs_json"), rs.getString("status"), rs.getBoolean("requires_human_confirm"),
                rs.getBigDecimal("confidence"), rs.getTimestamp("created_at").toLocalDateTime()), projectId);
    }

    public List<AliasCandidateView> listAliasCandidates(Long projectId) {
        return jdbcTemplate.query("""
                SELECT a.id, a.tom_id, t.name AS tom_name, a.alias_name, a.source_type, a.source_ref_id, a.status, a.created_at
                FROM tom_alias a JOIN test_object_model t ON t.id = a.tom_id
                WHERE a.project_id = ? AND a.status = 'CANDIDATE' ORDER BY a.created_at DESC, a.id DESC
                """, (rs, i) -> new AliasCandidateView(rs.getLong("id"), rs.getLong("tom_id"), rs.getString("tom_name"),
                rs.getString("alias_name"), rs.getString("source_type"), rs.getObject("source_ref_id") == null ? null : rs.getLong("source_ref_id"),
                rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime()), projectId);
    }

    public List<ImpactCandidateView> listImpactCandidates(Long projectId) {
        return jdbcTemplate.query("""
                SELECT e.id, e.from_model_id, t.name AS from_name, e.target_kind, e.target_ref, e.impact_type, e.source_ref_id, e.source_type, e.status, e.created_at
                FROM tom_impact_edge e JOIN test_object_model t ON t.id = e.from_model_id
                WHERE e.project_id = ? AND e.status = 'CANDIDATE' ORDER BY e.created_at DESC, e.id DESC
                """, (rs, i) -> new ImpactCandidateView(rs.getLong("id"), rs.getLong("from_model_id"), rs.getString("from_name"),
                rs.getString("target_kind"), rs.getString("target_ref"), rs.getString("impact_type"),
                rs.getObject("source_ref_id") == null ? null : rs.getLong("source_ref_id"), rs.getString("source_type"), rs.getString("status"),
                rs.getTimestamp("created_at").toLocalDateTime()), projectId);
    }

    @Transactional
    public RelationCandidateView confirmRelation(Long projectId, Long relationId, CurrentUser user) {
        RelationCandidateView relation = relation(projectId, relationId);
        if (!"CANDIDATE".equals(relation.status())) throw new IllegalStateException("该关系已被处理，请刷新后查看最新状态");
        Integer activeEndpoints = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM test_object_model
                WHERE id IN (?, ?) AND project_id = ? AND status = 'ACTIVE'
                """, Integer.class, relation.fromModelId(), relation.toModelId(), projectId);
        if (activeEndpoints == null || activeEndpoints != 2) {
            throw new IllegalStateException("请先确认该关系两端的 TOM 候选对象，再确认关系");
        }
        LocalDateTime now = timeProvider.now();
        int changed = jdbcTemplate.update("""
                UPDATE test_object_model_relation
                SET status = 'ACTIVE', requires_human_confirm = 0, confirmed_by = ?, confirmed_at = ?, updated_at = ?
                WHERE id = ? AND project_id = ? AND status = 'CANDIDATE'
                """, user == null || user.id() == null ? 0L : user.id(), now, now, relationId, projectId);
        if (changed != 1) throw new IllegalStateException("该关系已被其他用户处理，请刷新后查看最新状态");
        jdbcTemplate.update("UPDATE tom_evidence SET review_status = 'CONFIRMED' WHERE relation_id = ?", relationId);
        return relation(projectId, relationId);
    }

    @Transactional
    public RelationCandidateView rejectRelation(Long projectId, Long relationId, String reason, CurrentUser user) {
        RelationCandidateView relation = relation(projectId, relationId);
        if (!"CANDIDATE".equals(relation.status())) throw new IllegalStateException("该关系已被处理，请刷新后查看最新状态");
        LocalDateTime now = timeProvider.now();
        int changed = jdbcTemplate.update("""
                UPDATE test_object_model_relation
                SET status = 'REJECTED', rejected_by = ?, rejected_at = ?, rejected_reason = ?, updated_at = ?
                WHERE id = ? AND project_id = ? AND status = 'CANDIDATE'
                """, user == null || user.id() == null ? 0L : user.id(), now, safe(reason), now, relationId, projectId);
        if (changed != 1) throw new IllegalStateException("该关系已被其他用户处理，请刷新后查看最新状态");
        jdbcTemplate.update("UPDATE tom_evidence SET review_status = 'REJECTED' WHERE relation_id = ?", relationId);
        return relation(projectId, relationId);
    }

    @Transactional
    public void reviewAlias(Long projectId, Long aliasId, boolean confirm, String reason, CurrentUser user) {
        updateCandidateStatus("tom_alias", projectId, aliasId, confirm, reason, user);
    }

    @Transactional
    public void reviewImpact(Long projectId, Long impactId, boolean confirm, String reason, CurrentUser user) {
        updateCandidateStatus("tom_impact_edge", projectId, impactId, confirm, reason, user);
    }

    private void updateCandidateStatus(String table, Long projectId, Long id, boolean confirm, String reason, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        String sql = confirm
                ? "UPDATE " + table + " SET status = 'ACTIVE', requires_human_confirm = 0, confirmed_by = ?, confirmed_at = ?, updated_at = ? WHERE id = ? AND project_id = ? AND status = 'CANDIDATE'"
                : "UPDATE " + table + " SET status = 'REJECTED', rejected_by = ?, rejected_at = ?, rejected_reason = ?, updated_at = ? WHERE id = ? AND project_id = ? AND status = 'CANDIDATE'";
        int changed = confirm
                ? jdbcTemplate.update(sql, user == null || user.id() == null ? 0L : user.id(), now, now, id, projectId)
                : jdbcTemplate.update(sql, user == null || user.id() == null ? 0L : user.id(), now, safe(reason), now, id, projectId);
        if (changed != 1) throw new IllegalStateException("候选已被其他用户处理，请刷新后查看最新状态");
    }

    private RelationCandidateView relation(Long projectId, Long relationId) {
        List<RelationCandidateView> rows = jdbcTemplate.query("""
                SELECT r.id, r.project_id, r.from_model_id, f.name AS from_name, r.relation_type,
                       r.to_model_id, t.name AS to_name, r.source_type, r.source_ref_id,
                       r.source_refs_json, r.status, r.requires_human_confirm, r.confidence, r.created_at
                FROM test_object_model_relation r
                JOIN test_object_model f ON f.id = r.from_model_id
                JOIN test_object_model t ON t.id = r.to_model_id
                WHERE r.id = ? AND r.project_id = ?
                """, (rs, i) -> new RelationCandidateView(rs.getLong("id"), rs.getLong("project_id"),
                rs.getLong("from_model_id"), rs.getString("from_name"), rs.getString("relation_type"),
                rs.getLong("to_model_id"), rs.getString("to_name"), rs.getString("source_type"),
                rs.getObject("source_ref_id") == null ? null : rs.getLong("source_ref_id"),
                rs.getString("source_refs_json"), rs.getString("status"), rs.getBoolean("requires_human_confirm"),
                rs.getBigDecimal("confidence"), rs.getTimestamp("created_at").toLocalDateTime()), relationId, projectId);
        if (rows.isEmpty()) throw new IllegalStateException("候选关系不存在");
        return rows.get(0);
    }

    private Candidate create(Long projectId, String type, String name, String description, CaseRow row, String sourceHash, long actor, LocalDateTime now) {
        if (blank(name)) return null;
        // One TOM candidate aggregates corroborating formal cases.  A historic case is evidence,
        // not a reason to clone the same object once per source case.
        String key = stableCandidateKey(type, name);
        List<Long> existing = jdbcTemplate.queryForList("SELECT id FROM test_object_model WHERE project_id = ? AND candidate_key = ? ORDER BY id DESC LIMIT 1", Long.class, projectId, key);
        if (!existing.isEmpty()) {
            insertTomEvidence(projectId, existing.get(0), row, sourceHash, description, key, now);
            return new Candidate(existing.get(0), false, key, type, name);
        }
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("sourceType", row.sourceType()); refs.put("sourceRefType", row.sourceRefType()); refs.put("sourceRefId", row.id());
        refs.put("sourceVersion", 1); refs.put("caseNo", row.id()); refs.put("caseTitle", row.caseTitle());
        jdbcTemplate.update("""
                INSERT INTO test_object_model(project_id, scope, model_type, name, description, properties_json,
                    source_type, source_ref_id, source_context, source_refs_json, candidate_key, source_hash, source_version,
                    business_domain, priority, evidence_text, confidence, status, requires_human_confirm, validity_label,
                    created_by, created_at, updated_at)
                VALUES (?, 'PROJECT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 'MEDIUM', ?, 0.65,
                    'CANDIDATE', 1, 'TO_CONFIRM', ?, ?, ?)
                """, projectId, type, clip(name, 256), clip(description, 10000), json(Map.of("historicalCaseId", row.id(), "caseType", safe(row.caseType()), "scenarioType", safe(row.scenarioType()), "designMethod", safe(row.designMethod()))),
                row.sourceType(), row.id(), clip(description, 2000), json(refs), key, sourceHash, clip(row.moduleName(), 64), clip(description, 2000), actor, now, now);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        insertTomEvidence(projectId, id, row, sourceHash, description, key, now);
        return new Candidate(id, true, key, type, name);
    }

    private void insertTomEvidence(Long projectId, Long tomId, CaseRow row, String sourceHash,
                                   String description, String key, LocalDateTime now) {
        Integer exists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tom_evidence
                WHERE project_id = ? AND tom_id = ? AND source_type = ?
                  AND source_ref_id = ? AND source_hash = ?
                """, Integer.class, projectId, tomId, row.sourceType(), row.id(), sourceHash);
        if (exists != null && exists > 0) return;
        jdbcTemplate.update("""
                INSERT INTO tom_evidence(project_id, tom_id, source_type, source_ref_type, source_ref_id, source_version,
                    source_hash, source_excerpt, evidence_level, conflict_status, review_status, candidate_key, created_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, 'HISTORICAL', 'NONE', 'PENDING', ?, ?)
                """, projectId, tomId, row.sourceType(), row.sourceRefType(), row.id(), sourceHash, clip(description, 4000), key, now);
    }

    private boolean relation(Long projectId, Candidate from, Candidate to, String type, CaseRow row, String hash, long actor, LocalDateTime now) {
        if (from == null || to == null || from.id() == null || to.id() == null) return false;
        String key = row.sourceType() + ":" + row.id() + ":REL:" + type + ":" + from.key() + ":" + to.key();
        Integer existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM test_object_model_relation WHERE project_id = ? AND candidate_key = ? AND source_hash = ?", Integer.class, projectId, key, hash);
        if (existing != null && existing > 0) return false;
        Map<String, Object> refs = Map.of("sourceType", row.sourceType(), "sourceRefType", row.sourceRefType(), "sourceRefId", row.id());
        jdbcTemplate.update("""
                INSERT INTO test_object_model_relation(project_id, from_model_id, relation_type, to_model_id, confidence,
                    status, source_type, source_ref_id, source_refs_json, candidate_key, source_hash, source_version,
                    requires_human_confirm, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0.65, 'CANDIDATE', ?, ?, ?, ?, ?, 1, 1, ?, ?)
                """, projectId, from.id(), type, to.id(), row.sourceType(), row.id(), json(refs), key, hash, now, now);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("""
                INSERT INTO tom_evidence(project_id, relation_id, source_type, source_ref_type, source_ref_id, source_version,
                    source_hash, source_excerpt, evidence_level, conflict_status, review_status, candidate_key, created_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, 'HISTORICAL', 'NONE', 'PENDING', ?, ?)
                """, projectId, id, row.sourceType(), row.sourceRefType(), row.id(), hash, clip(row.caseTitle(), 4000), key, now);
        return true;
    }
    private void insertExplicitAliases(Long projectId, Candidate candidate, CaseRow row, String sourceHash, LocalDateTime now) {
        if (candidate == null || blank(candidate.name())) return;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[（(]([^（）()]{2,64})[）)]").matcher(candidate.name());
        while (matcher.find()) {
            String alias = matcher.group(1).trim();
            if (alias.equals(candidate.name())) continue;
            Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tom_alias WHERE project_id = ? AND tom_id = ? AND alias_name = ? AND source_hash = ?", Integer.class, projectId, candidate.id(), alias, sourceHash);
            if (exists != null && exists > 0) continue;
            jdbcTemplate.update("""
                    INSERT INTO tom_alias(project_id, tom_id, alias_name, alias_type, source_type, source_ref_id,
                        source_hash, status, requires_human_confirm, created_at, updated_at)
                    VALUES (?, ?, ?, 'HISTORICAL_TERM', ?, ?, ?, 'CANDIDATE', 1, ?, ?)
                    """, projectId, candidate.id(), alias, row.sourceType(), row.id(), sourceHash, now, now);
        }
    }
    private void impact(Long projectId, Candidate from, String targetKind, String targetRef, String impactType,
                        CaseRow row, String sourceHash, LocalDateTime now) {
        if (from == null || blank(targetRef)) return;
        Integer exists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tom_impact_edge
                WHERE project_id = ? AND from_model_id = ? AND target_kind = ? AND target_ref = ?
                  AND impact_type = ? AND source_hash = ?
                """, Integer.class, projectId, from.id(), targetKind, clip(targetRef, 256), impactType, sourceHash);
        if (exists != null && exists > 0) return;
        jdbcTemplate.update("""
                INSERT INTO tom_impact_edge(project_id, from_model_id, target_kind, target_ref, impact_type,
                    source_type, source_ref_id, source_hash, evidence_json, status, requires_human_confirm, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CANDIDATE', 1, ?, ?)
                """, projectId, from.id(), targetKind, clip(targetRef, 256), impactType, row.sourceType(), row.id(), sourceHash,
                json(Map.of("sourceCaseId", row.id(), "sourceCaseTitle", safe(row.caseTitle()))), now, now);
    }
    private Candidate extractState(String expected) { if (blank(expected)) return null; for (String marker : List.of("→", "->", "变为", "变成")) { int i = expected.indexOf(marker); if (i >= 0) { String after = expected.substring(i + marker.length()).trim(); if (!after.isBlank()) return new Candidate(null, false, "", "STATE", clip(after, 128)); } } return null; }
    private int count(Candidate a, Candidate b, Candidate c, Candidate d, Candidate e, boolean created) { int n = 0; for (Candidate candidate : new Candidate[]{a,b,c,d,e}) if (candidate != null && candidate.created() == created) n++; return n; }
    private ExtractionResult plus(ExtractionResult a, ExtractionResult b) { return new ExtractionResult(a.candidates()+b.candidates(), a.relations()+b.relations(), a.unchanged()+b.unchanged(), a.skipped()+b.skipped()); }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception e) { return "{}"; } }
    static String stableCandidateKey(String type, String name) {
        return "FORMAL_CASE_TOM:" + safeStatic(type).trim().toUpperCase(java.util.Locale.ROOT)
                + ":" + sha256Static(safeStatic(name).trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT)).substring(0, 24);
    }
    /**
     * A formally submitted case is still only evidence.  Do not turn sample text,
     * unresolved assumptions, or title-only placeholders into project TOM candidates.
     */
    static boolean isEligibleForExtraction(String title, String module, String precondition,
                                           String steps, String expectedResult) {
        String anchor = joinStatic(title, module, precondition, steps, expectedResult).trim();
        if (anchor.isBlank() || (blankStatic(steps) && blankStatic(expectedResult))) return false;
        String normalized = anchor.toLowerCase(java.util.Locale.ROOT);
        for (String marker : List.of("假设", "待确认", "演示", "样例", "示例", "todo", "tbd")) {
            if (normalized.contains(marker)) return false;
        }
        String compactTitle = safeStatic(title).trim().replaceAll("[\\s：:，,。.]", "");
        return !List.of("测试", "验证", "功能测试", "回归测试", "通用测试").contains(compactTitle);
    }
    private static String sha256Static(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String safeStatic(String value) { return value == null ? "" : value; }
    private static boolean blankStatic(String value) { return value == null || value.isBlank(); }
    private static String joinStatic(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) result.append(safeStatic(value)).append('\n');
        return result.toString();
    }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String join(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append('\n');
            result.append(safe(value));
        }
        return result.toString();
    }
    private String safe(String value) { return value == null ? "" : value; }
    private String normalize(String value) { return safe(value).trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT); }
    private String first(String a, String b) { return blank(a) ? b : a; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String clip(String value, int max) { String v = safe(value); return v.length() <= max ? v : v.substring(0, max); }
    private record Candidate(Long id, boolean created, String key, String type, String name) {}
    private record CaseRow(Long id, Long projectId, String caseTitle, String moduleName, String precondition, String steps, String expectedResult, String priority, String caseType, String scenarioType, String designMethod, String sourceRefsJson, String sourceType, String sourceRefType) {
        private CaseRow(Long id, Long projectId, String caseTitle, String moduleName, String precondition, String steps, String expectedResult, String priority, String caseType, String scenarioType, String designMethod, String sourceRefsJson) {
            this(id, projectId, caseTitle, moduleName, precondition, steps, expectedResult, priority, caseType, scenarioType, designMethod, sourceRefsJson, "FORMAL_TEST_CASE", "TEST_CASE_ASSET");
        }
    }
}

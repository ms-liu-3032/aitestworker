package com.company.aitest.knowledge;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts confirmed project activity into reviewable Wiki and project TOM candidates.
 * Candidates are never activated here; Wiki and TOM keep their existing review flows.
 */
@Service
public class KnowledgeDepositionService {

    private static final String AUTO_PACK_NAME = "项目自动沉淀";
    private static final int WIKI_CHUNK_CHARS = 8_000;
    private static final int MAX_WIKI_TEST_POINTS_PER_ENTRY = 30;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;

    public KnowledgeDepositionService(JdbcClient jdbc, JdbcTemplate jdbcTemplate,
                                      TimeProvider timeProvider, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        this.objectMapper = objectMapper;
    }

    public record DepositionResult(int wikiCandidates, int tomCandidates, int unchanged) {
        public DepositionResult plus(DepositionResult other) {
            return new DepositionResult(wikiCandidates + other.wikiCandidates,
                    tomCandidates + other.tomCandidates, unchanged + other.unchanged);
        }
    }

    @Transactional
    public DepositionResult depositUploadedDocument(Long projectId, String sourceRefType, Long sourceRefId,
                                                     String title, String content, Long createdBy) {
        if (projectId == null || content == null || content.isBlank()) return new DepositionResult(0, 0, 0);
        Long packId = ensureAutoPack(projectId, createdBy);
        int created = 0;
        int unchanged = 0;
        List<String> chunks = split(content, WIKI_CHUNK_CHARS);
        String documentHash = sha256(content);
        for (int i = 0; i < chunks.size(); i++) {
            String suffix = chunks.size() == 1 ? "" : "（" + (i + 1) + "/" + chunks.size() + "）";
            boolean inserted = insertWikiCandidate(packId, "IMPLEMENTATION", safeTitle(title) + suffix,
                    chunks.get(i), "UPLOAD", sourceRefType, sourceRefId,
                    "UPLOAD:" + documentHash + ":" + (i + 1),
                    sourceRefs("UPLOAD", sourceRefType, sourceRefId, null, null), createdBy);
            if (inserted) created++; else unchanged++;
        }
        return new DepositionResult(created, 0, unchanged);
    }

    @Transactional
    public DepositionResult depositConfirmedRequirement(Long projectId, Long analysisId, int analysisVersion,
                                                         String analysisJson, String clarificationQuestionsJson,
                                                         String clarificationAnswersJson, CurrentUser user) {
        if (projectId == null || analysisId == null) return new DepositionResult(0, 0, 0);
        Long createdBy = user == null ? 1L : user.id();
        Long packId = ensureAutoPack(projectId, createdBy);
        Map<String, Object> analysis = object(analysisJson);
        if (analysis == null) return new DepositionResult(0, 0, 0);

        DepositionResult total = new DepositionResult(0, 0, 0);
        String understanding = text(analysis.get("requirement_understanding"));
        if (!understanding.isBlank()) {
            boolean inserted = insertWikiCandidate(packId, "HISTORY", "需求分析 v" + analysisVersion,
                    understanding, "REQUIREMENT_ANALYSIS", "REQUIREMENT_ANALYSIS", analysisId,
                    "ANALYSIS:" + analysisId + ":SUMMARY",
                    sourceRefs("REQUIREMENT_ANALYSIS", "REQUIREMENT_ANALYSIS", analysisId,
                            analysisVersion, null), createdBy);
            total = total.plus(new DepositionResult(inserted ? 1 : 0, 0, inserted ? 0 : 1));
        }

        String businessDomain = text(analysis.get("business_domain"));
        for (Map<String, Object> atom : objectList(analysis.get("requirement_atoms"))) {
            String atomId = firstNonBlank(text(atom.get("id")), "R" + (total.wikiCandidates() + 1));
            if ("EXCLUDED".equalsIgnoreCase(text(atom.get("generation_scope")))) continue;
            String title = firstNonBlank(text(atom.get("title")), text(atom.get("requirement")), atomId);
            String requirement = firstNonBlank(text(atom.get("requirement")), text(atom.get("description")), title);
            String category = text(atom.get("category"));
            String refs = sourceRefs("REQUIREMENT_ANALYSIS", "REQUIREMENT_ATOM", analysisId,
                    analysisVersion, atomId);
            boolean wiki = insertWikiCandidate(packId, "RULE", title, requirement,
                    "REQUIREMENT_ANALYSIS", "REQUIREMENT_ATOM", analysisId,
                    "REQUIREMENT:" + normalizeKey(category) + ":" + semanticKey(title), refs, createdBy);
            boolean tom = insertTomCandidate(projectId, inferTomType(category), title, requirement,
                    businessDomain, "REQUIREMENT_ANALYSIS", analysisId,
                    "REQUIREMENT:" + normalizeKey(category) + ":" + semanticKey(title), refs,
                    properties(atom, Map.of("requirementRef", atomId, "analysisVersion", analysisVersion)),
                    new BigDecimal("0.80"), createdBy);
            total = total.plus(new DepositionResult(wiki ? 1 : 0, tom ? 1 : 0,
                    (wiki ? 0 : 1) + (tom ? 0 : 1)));
        }

        List<Map<String, Object>> questions = parseObjectList(clarificationQuestionsJson);
        for (Map<String, Object> answer : parseObjectList(clarificationAnswersJson)) {
            int index = integer(answer.get("index"), -1);
            String answerText = text(answer.get("answer"));
            if (answerText.isBlank()) continue;
            Map<String, Object> question = index >= 0 && index < questions.size() ? questions.get(index) : Map.of();
            String questionText = firstNonBlank(text(question.get("question")), "需求澄清 " + (index + 1));
            String content = "问题：" + questionText + "\n结论：" + answerText;
            boolean inserted = insertWikiCandidate(packId, "DECISION", questionText, content,
                    "REQUIREMENT_ANALYSIS", "CLARIFICATION", analysisId,
                    "CLARIFICATION:" + semanticKey(questionText),
                    sourceRefs("REQUIREMENT_ANALYSIS", "CLARIFICATION", analysisId,
                            analysisVersion, String.valueOf(index)), createdBy);
            total = total.plus(new DepositionResult(inserted ? 1 : 0, 0, inserted ? 0 : 1));
        }
        return total;
    }

    @Transactional
    public DepositionResult depositConfirmedTestPoints(Long projectId, Long analysisId, int analysisVersion,
                                                        String testPointsJson, CurrentUser user) {
        if (projectId == null || analysisId == null) return new DepositionResult(0, 0, 0);
        Long createdBy = user == null ? 1L : user.id();
        List<Map<String, Object>> points = parseObjectList(testPointsJson).stream()
                .filter(point -> !"EXCLUDED".equalsIgnoreCase(text(point.get("generation_scope"))))
                .toList();
        if (points.isEmpty()) return new DepositionResult(0, 0, 0);
        Long packId = ensureAutoPack(projectId, createdBy);
        DepositionResult total = new DepositionResult(0, 0, 0);

        Map<String, List<Map<String, Object>>> byModule = new LinkedHashMap<>();
        for (Map<String, Object> point : points) {
            byModule.computeIfAbsent(firstNonBlank(text(point.get("module")), "未分类"), key -> new ArrayList<>())
                    .add(point);
            String pointId = firstNonBlank(text(point.get("id")), "TP" + (total.tomCandidates() + 1));
            String title = firstNonBlank(text(point.get("title")), text(point.get("description")), pointId);
            String description = firstNonBlank(text(point.get("description")), title);
            String refs = sourceRefs("TEST_POINT", "TEST_POINT", analysisId, analysisVersion, pointId);
            boolean tom = insertTomCandidate(projectId, "ASSERTION", title, description,
                    text(point.get("module")), "TEST_POINT", analysisId,
                    "TEST_POINT:" + semanticKey(text(point.get("module"))) + ":" + semanticKey(title), refs,
                    properties(point, Map.of("testPointRef", pointId, "analysisVersion", analysisVersion)),
                    new BigDecimal("0.75"), createdBy);
            total = total.plus(new DepositionResult(0, tom ? 1 : 0, tom ? 0 : 1));
        }

        for (Map.Entry<String, List<Map<String, Object>>> module : byModule.entrySet()) {
            List<Map<String, Object>> modulePoints = module.getValue();
            for (int from = 0, part = 1; from < modulePoints.size(); from += MAX_WIKI_TEST_POINTS_PER_ENTRY, part++) {
                List<Map<String, Object>> batch = modulePoints.subList(from,
                        Math.min(modulePoints.size(), from + MAX_WIKI_TEST_POINTS_PER_ENTRY));
                StringBuilder content = new StringBuilder();
                for (Map<String, Object> point : batch) {
                    content.append("- ").append(firstNonBlank(text(point.get("title")), text(point.get("description"))))
                            .append(" [").append(text(point.get("id"))).append("]\n");
                }
                String key = "TEST_POINT_SET:" + semanticKey(module.getKey()) + ":" + part;
                boolean wiki = insertWikiCandidate(packId, "HISTORY",
                        module.getKey() + "测试点清单" + (modulePoints.size() > MAX_WIKI_TEST_POINTS_PER_ENTRY ? "（" + part + "）" : ""),
                        content.toString().trim(), "TEST_POINT", "TEST_POINT_SET", analysisId,
                        key, sourceRefs("TEST_POINT", "TEST_POINT_SET", analysisId,
                                analysisVersion, module.getKey()), createdBy);
                total = total.plus(new DepositionResult(wiki ? 1 : 0, 0, wiki ? 0 : 1));
            }
        }
        return total;
    }

    /**
     * Unified Loop-to-Wiki candidate route. Loop events remain reviewable and inactive
     * until the existing Wiki review and activation gates are completed.
     */
    @Transactional
    public DepositionResult depositLoopWikiCandidate(Long projectId, Long clusterId, Long eventId,
                                                      String eventType, String sourceStage, String theme,
                                                      String content, Long createdBy) {
        if (projectId == null || eventId == null || content == null || content.isBlank()) {
            return new DepositionResult(0, 0, 0);
        }
        String entryType = switch (text(eventType)) {
            case "TOM_STRATEGY" -> "DECISION";
            case "TRACE_SUMMARY_QUALITY" -> "HISTORY";
            case "LOCALIZATION_CHECK" -> "FAQ";
            default -> "RULE";
        };
        Long packId = ensureAutoPack(projectId, createdBy);
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("sourceType", "LOOP");
        refs.put("sourceRefType", "LOOP_EVENT");
        refs.put("sourceRefId", eventId);
        refs.put("clusterId", clusterId);
        refs.put("eventType", eventType);
        refs.put("sourceStage", sourceStage);
        boolean inserted = insertWikiCandidate(packId, entryType,
                firstNonBlank(theme, eventType, "Loop 回灌建议"), content,
                "LOOP", "LOOP_EVENT", eventId,
                "LOOP:" + semanticKey(eventType) + ":" + semanticKey(theme) + ":" + eventId,
                json(refs), createdBy);
        return new DepositionResult(inserted ? 1 : 0, 0, inserted ? 0 : 1);
    }

    private Long ensureAutoPack(Long projectId, Long createdBy) {
        jdbcTemplate.update("""
                INSERT INTO wiki_pack(project_id, scope, name, status, review_status, source_type,
                    description, created_by, created_at, updated_at)
                VALUES (?, 'PROJECT', ?, 'DRAFT', 'PENDING', 'AUTO_DEPOSITION',
                    '由上传资料、需求澄清、需求分析和测试点生成的待审核知识候选', ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    status = CASE WHEN review_status = 'REJECTED' THEN 'DRAFT' ELSE status END,
                    review_status = CASE WHEN review_status = 'REJECTED' THEN 'PENDING' ELSE review_status END,
                    updated_at = VALUES(updated_at)
                """, projectId, AUTO_PACK_NAME, createdBy, timeProvider.now(), timeProvider.now());
        return jdbc.sql("SELECT id FROM wiki_pack WHERE project_id = ? AND name = ?")
                .param(projectId).param(AUTO_PACK_NAME).query(Long.class).single();
    }

    private boolean insertWikiCandidate(Long packId, String entryType, String title, String content,
                                        String sourceType, String sourceRefType, Long sourceRefId,
                                        String candidateKey, String sourceRefsJson, Long createdBy) {
        String normalizedContent = clip(content, 60_000);
        String hash = sha256(entryType + "\n" + title + "\n" + normalizedContent);
        List<Map<String, Object>> latest = jdbcTemplate.queryForList("""
                SELECT content_hash, source_version FROM wiki_entry
                WHERE pack_id = ? AND candidate_key = ?
                ORDER BY source_version DESC, id DESC LIMIT 1
                """, packId, candidateKey);
        if (!latest.isEmpty() && Objects.equals(hash, text(latest.get(0).get("content_hash")))) return false;
        int version = latest.isEmpty() ? 1 : integer(latest.get(0).get("source_version"), 0) + 1;
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO wiki_entry(pack_id, entry_type, title, content, source_refs_json,
                    candidate_key, source_type, source_ref_type, source_ref_id, source_version, content_hash,
                    review_status, confidence, effective_status, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0.70, 'INACTIVE', ?, ?, ?)
                """, packId, entryType, clip(title, 512), normalizedContent, sourceRefsJson,
                clip(candidateKey, 191), sourceType, sourceRefType, sourceRefId, version, hash,
                createdBy, now, now);
        return true;
    }

    private boolean insertTomCandidate(Long projectId, String modelType, String name, String description,
                                       String businessDomain, String sourceType, Long sourceRefId,
                                       String candidateKey, String sourceRefsJson, String propertiesJson,
                                       BigDecimal confidence, Long createdBy) {
        String normalizedDescription = clip(description, 10_000);
        String hash = sha256(modelType + "\n" + name + "\n" + normalizedDescription + "\n" + propertiesJson);
        List<Map<String, Object>> latest = jdbcTemplate.queryForList("""
                SELECT source_hash, source_version FROM test_object_model
                WHERE project_id = ? AND candidate_key = ?
                ORDER BY source_version DESC, id DESC LIMIT 1
                """, projectId, candidateKey);
        if (!latest.isEmpty() && Objects.equals(hash, text(latest.get(0).get("source_hash")))) return false;
        int version = latest.isEmpty() ? 1 : integer(latest.get(0).get("source_version"), 0) + 1;
        LocalDateTime now = timeProvider.now();
        jdbcTemplate.update("""
                INSERT INTO test_object_model(project_id, scope, model_type, name, description, properties_json,
                    source_type, source_ref_id, source_context, source_refs_json, candidate_key, source_hash,
                    source_version, business_domain, priority, evidence_text, confidence, status,
                    requires_human_confirm, validity_label, created_by, created_at, updated_at)
                VALUES (?, 'PROJECT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'MEDIUM', ?, ?,
                    'CANDIDATE', 1, 'TO_CONFIRM', ?, ?, ?)
                """, projectId, modelType, clip(name, 256), normalizedDescription, propertiesJson,
                sourceType, sourceRefId, clip(normalizedDescription, 2_000), sourceRefsJson,
                clip(candidateKey, 191), hash, version, clip(businessDomain, 64),
                clip(normalizedDescription, 2_000), confidence, createdBy, now, now);
        return true;
    }

    private String properties(Map<String, Object> source, Map<String, Object> extra) {
        Map<String, Object> properties = new LinkedHashMap<>();
        copy(properties, source, "category", "point_type", "design_method", "priority_hint",
                "requirement_refs", "source_basis", "generation_scope");
        properties.putAll(extra);
        return json(properties);
    }

    private void copy(Map<String, Object> target, Map<String, Object> source, String... keys) {
        for (String key : keys) if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private String inferTomType(String category) {
        String value = category == null ? "" : category.toUpperCase(Locale.ROOT);
        if (value.contains("MODULE")) return "MODULE";
        if (value.contains("PAGE") || value.contains("UI") || value.contains("FORM")) return "PAGE";
        if (value.contains("FIELD") || value.contains("DATA")) return "FIELD";
        if (value.contains("ROLE") || value.contains("AUTH") || value.contains("PERMISSION")) return "ROLE";
        if (value.contains("FLOW") || value.contains("PROCESS")) return "FLOW";
        if (value.contains("STATE") || value.contains("STATUS")) return "STATE";
        if (value.contains("ACTION") || value.contains("INTEGRATION")) return "ACTION";
        return "ASSERTION";
    }

    private String sourceRefs(String sourceType, String sourceRefType, Long sourceRefId,
                              Integer sourceVersion, String itemRef) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("sourceType", sourceType);
        refs.put("sourceRefType", sourceRefType);
        refs.put("sourceRefId", sourceRefId);
        if (sourceVersion != null) refs.put("sourceVersion", sourceVersion);
        if (itemRef != null && !itemRef.isBlank()) refs.put("itemRef", itemRef);
        return json(refs);
    }

    private Map<String, Object> object(String json) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception ignored) { return null; }
    }

    private List<Map<String, Object>> parseObjectList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception ignored) { return List.of(); }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objectList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        return result;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("无法序列化沉淀资产来源", e); }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算知识候选摘要", e);
        }
    }

    private List<String> split(String value, int size) {
        List<String> result = new ArrayList<>();
        for (int start = 0; start < value.length(); start += size) {
            result.add(value.substring(start, Math.min(value.length(), start + size)));
        }
        return result;
    }

    private String normalizeKey(String value) {
        return value == null ? "UNKNOWN" : value.trim().replaceAll("[^\\p{IsHan}A-Za-z0-9_-]+", "_");
    }

    private String semanticKey(String value) {
        String normalized = normalizeKey(value).toLowerCase(Locale.ROOT);
        if (normalized.length() <= 96) return normalized;
        return normalized.substring(0, 64) + "_" + sha256(normalized).substring(0, 24);
    }

    private String safeTitle(String value) { return firstNonBlank(value, "上传资料"); }
    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank() && !"null".equals(value)) return value.trim();
        return "";
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(text(value)); } catch (Exception ignored) { return fallback; }
    }
    private String clip(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}

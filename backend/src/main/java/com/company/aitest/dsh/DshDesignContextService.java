package com.company.aitest.dsh;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.minitom.MiniTomService;
import com.company.aitest.minitom.TestObjectModelRecord;
import com.company.aitest.wiki.WikiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Supplies governed facts to DSH; all interpretation and case design stay in the local agent. */
@Service
public class DshDesignContextService {
    private static final Logger log = LoggerFactory.getLogger(DshDesignContextService.class);
    private static final Pattern WORDS = Pattern.compile("[\\p{IsHan}]+|[A-Za-z0-9_]{2,}");
    private final MiniTomService miniTom;
    private final WikiService wiki;
    private final JdbcClient jdbc;

    public DshDesignContextService(MiniTomService miniTom, WikiService wiki, JdbcClient jdbc) {
        this.miniTom = miniTom;
        this.wiki = wiki;
        this.jdbc = jdbc;
    }

    public Map<String, Object> build(Long projectId, String requirementText, String mode, CurrentUser user) {
        String requirement = requirementText == null ? "" : requirementText.strip();
        if (requirement.isEmpty() || requirement.length() > 20_000) {
            throw new BusinessException("需求正文须为 1 至 20000 字");
        }
        if (mode == null || !Set.of("DIRECT", "PROJECT_TOM", "PROJECT_AND_SYSTEM_TOM").contains(mode)) {
            throw new BusinessException("TOM 模式无效");
        }
        List<String> terms = terms(requirement);
        List<TestObjectModelRecord> visible = "DIRECT".equals(mode) ? List.of() : miniTom.listActive(projectId, null, user).stream()
                .filter(item -> "PROJECT_AND_SYSTEM_TOM".equals(mode) || "PROJECT".equals(item.scope()))
                .toList();
        List<TestObjectModelRecord> ranked = visible.stream()
                .filter(item -> score(item, terms) > 0)
                .sorted(Comparator.<TestObjectModelRecord>comparingInt(item -> score(item, terms)).reversed()
                        .thenComparing(item -> "PROJECT".equals(item.scope()) ? 0 : 1)
                        .thenComparing(TestObjectModelRecord::id))
                .toList();
        // Keep the strongest facts while reserving space for each relevant skeleton type.
        // A flat top-24 list can otherwise contain only pages/actions and hide roles/states.
        LinkedHashMap<Long, TestObjectModelRecord> seedMap = new LinkedHashMap<>();
        for (String type : List.of("ROLE", "FLOW", "STATE", "ACTION", "MODULE", "PAGE", "ASSERTION", "FIELD")) {
            ranked.stream().filter(item -> type.equals(item.modelType())).limit(2)
                    .forEach(item -> seedMap.put(item.id(), item));
        }
        for (TestObjectModelRecord item : ranked) {
            if (seedMap.size() >= 24) break;
            seedMap.putIfAbsent(item.id(), item);
        }
        List<TestObjectModelRecord> seeds = List.copyOf(seedMap.values());
        Map<Long, TestObjectModelRecord> byId = new LinkedHashMap<>();
        for (TestObjectModelRecord item : visible) byId.put(item.id(), item);
        long confirmedRelationAvailableCount = visible.isEmpty() ? 0L : jdbc.sql("""
                SELECT COUNT(*) FROM test_object_model_relation
                WHERE status IN ('CONFIRMED', 'ACTIVE')
                  AND from_model_id IN (:ids) AND to_model_id IN (:ids)
                """).param("ids", List.copyOf(byId.keySet())).query(Long.class).single();
        LinkedHashSet<Long> selectedIds = new LinkedHashSet<>();
        for (TestObjectModelRecord seed : seeds) selectedIds.add(seed.id());
        List<Map<String, Object>> relations = new ArrayList<>();
        if (!selectedIds.isEmpty()) {
            List<Map<String, Object>> neighbors = jdbc.sql("""
                    SELECT id, from_model_id, to_model_id, relation_type, status, source_type, source_ref_id
                    FROM test_object_model_relation
                    WHERE status IN ('CONFIRMED', 'ACTIVE')
                      AND (from_model_id IN (:ids) OR to_model_id IN (:ids))
                    ORDER BY id DESC LIMIT 300
                    """).param("ids", List.copyOf(selectedIds))
                    .query((rs, row) -> {
                        Map<String, Object> edge = new LinkedHashMap<>();
                        edge.put("id", rs.getLong("id"));
                        edge.put("fromTomId", rs.getLong("from_model_id"));
                        edge.put("toTomId", rs.getLong("to_model_id"));
                        edge.put("relationType", rs.getString("relation_type"));
                        edge.put("status", rs.getString("status"));
                        edge.put("sourceType", rs.getString("source_type"));
                        edge.put("sourceRefId", rs.getObject("source_ref_id"));
                        return edge;
                    }).list();
            for (Map<String, Object> edge : neighbors) {
                Long from = (Long) edge.get("fromTomId");
                Long to = (Long) edge.get("toTomId");
                if (!byId.containsKey(from) || !byId.containsKey(to)) continue;
                if (selectedIds.size() >= 50 && (!selectedIds.contains(from) || !selectedIds.contains(to))) continue;
                selectedIds.add(from);
                selectedIds.add(to);
                relations.add(edge);
                if (relations.size() >= 100) break;
            }
        }
        List<Map<String, Object>> nodes = selectedIds.stream().map(byId::get).map(this::node).toList();
        List<Map<String, Object>> linkedWiki = new ArrayList<>();
        if (!selectedIds.isEmpty()) {
            linkedWiki = jdbc.sql("""
                    SELECT we.id, we.title, we.entry_type, we.content, we.source_refs_json,
                           we.source_type, we.source_ref_type, we.source_ref_id,
                           wp.id AS pack_id, wp.name AS pack_name, wp.scope, link.related_tom_id
                    FROM wiki_entry_relation link
                    JOIN wiki_entry we ON we.id = link.entry_id
                    JOIN wiki_pack wp ON wp.id = we.pack_id
                    WHERE link.related_tom_id IN (:ids)
                      AND wp.status = 'ACTIVE' AND wp.review_status = 'APPROVED'
                      AND we.review_status = 'APPROVED' AND we.effective_status = 'ACTIVE'
                      AND (wp.project_id = :projectId OR wp.scope IN ('REUSABLE', 'SYSTEM'))
                    ORDER BY CASE WHEN we.entry_type = 'HISTORY' OR we.source_type IN
                                      ('IMPORTED_HISTORICAL_CASE', 'FORMAL_TEST_CASE') THEN 1 ELSE 0 END,
                             CASE wp.scope WHEN 'PROJECT' THEN 1 WHEN 'REUSABLE' THEN 2 ELSE 3 END,
                             we.id DESC LIMIT 80
                    """).param("ids", List.copyOf(selectedIds)).param("projectId", projectId)
                    .query((rs, row) -> {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("entryId", rs.getLong("id"));
                        entry.put("relatedTomId", rs.getLong("related_tom_id"));
                        entry.put("packId", rs.getLong("pack_id"));
                        entry.put("packName", rs.getString("pack_name"));
                        entry.put("scope", rs.getString("scope"));
                        entry.put("entryType", rs.getString("entry_type"));
                        entry.put("sourceType", rs.getString("source_type"));
                        entry.put("sourceRefType", rs.getString("source_ref_type"));
                        entry.put("sourceRefId", rs.getObject("source_ref_id"));
                        entry.put("evidenceRole", WikiService.evidenceRole(rs.getString("entry_type"), rs.getString("source_type")));
                        entry.put("title", rs.getString("title"));
                        entry.put("content", rs.getString("content"));
                        entry.put("sourceRefsJson", rs.getString("source_refs_json"));
                        return entry;
                    }).list();
            Map<Long, Map<String, Object>> unique = new LinkedHashMap<>();
            for (Map<String, Object> row : linkedWiki) {
                Long entryId = (Long) row.get("entryId");
                Map<String, Object> entry = unique.computeIfAbsent(entryId, ignored -> {
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    copy.remove("relatedTomId");
                    copy.put("relatedTomIds", new ArrayList<Long>());
                    return copy;
                });
                @SuppressWarnings("unchecked")
                List<Long> ids = (List<Long>) entry.get("relatedTomIds");
                ids.add((Long) row.get("relatedTomId"));
            }
            linkedWiki = unique.values().stream().limit(12).toList();
        }
        List<String> fallbackTerms = wikiTerms(terms, seeds);
        Map<String, Object> fallback = wikiFallback(projectId, fallbackTerms);
        String caseTerm = terms.stream().filter(term -> term.length() >= 2 && term.length() <= 4)
                .filter(term -> seeds.isEmpty() || seeds.stream().anyMatch(node -> safe(node.name()).contains(term)))
                .findFirst().orElse("");
        List<Map<String, Object>> formalCases = caseTerm.isEmpty() ? List.of() : jdbc.sql("""
                SELECT id, case_no, case_title, module_name, precondition, steps, expected_result,
                       import_source_type, import_source_ref_id
                FROM test_case_asset
                WHERE project_id = :projectId AND case_status = 'SUBMITTED' AND deprecated_at IS NULL
                  AND (case_scope IS NULL OR case_scope IN ('PROJECT', 'PUBLIC', 'SYSTEM')
                       OR (case_scope = 'PERSONAL' AND submitted_by = :userId))
                  AND (case_title LIKE :pattern ESCAPE '!' OR module_name LIKE :pattern ESCAPE '!'
                       OR steps LIKE :pattern ESCAPE '!')
                ORDER BY id DESC LIMIT 8
                """).param("projectId", projectId).param("userId", user.id())
                .param("pattern", likePattern(caseTerm))
                .query((rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("caseNo", rs.getString("case_no"));
                    item.put("caseTitle", rs.getString("case_title"));
                    item.put("moduleName", rs.getString("module_name"));
                    item.put("precondition", rs.getString("precondition"));
                    item.put("steps", rs.getString("steps"));
                    item.put("expectedResult", rs.getString("expected_result"));
                    item.put("importSourceType", rs.getString("import_source_type"));
                    item.put("importSourceRefId", rs.getObject("import_source_ref_id"));
                    return item;
                }).list();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tomMode", mode);
        result.put("tomAvailableCount", visible.size());
        result.put("tomTypeCoverage", typeCoverage(visible, ranked, nodes));
        result.put("tomSelectedCount", nodes.size());
        result.put("tomConfirmedRelationAvailableCount", confirmedRelationAvailableCount);
        result.put("tomNodes", nodes);
        result.put("tomRelations", relations);
        result.put("wikiLinked", linkedWiki);
        result.put("wikiSearchFallback", fallback);
        result.put("wikiFallbackQuery", fallbackTerms.isEmpty() ? "" : fallbackTerms.get(0));
        result.put("wikiFallbackQueries", fallbackTerms);
        result.put("formalCases", formalCases);
        result.put("formalCaseMatchTerm", caseTerm);
        result.put("formalCaseRole", "SUPPLEMENTARY_COVERAGE_STEPS_STYLE");
        result.put("selectionNotice", "TOM 是角色、流程、动作、状态及关系骨架；关联 Wiki 是语义和实现正文。缺少角色、状态或已确认关系时，应自动补查并报告来源缺口，不得推断关系。未关联 Wiki 仅为检索候选，须读取详情并核实，不能当作已确认关系。正式用例用于覆盖、步骤和风格参考。");
        log.info("dsh_design_context projectId={} userId={} tomMode={} tomAvailable={} tomSelected={} relations={} wikiLinked={} wikiFallbackMatches={} formalCases={}",
                projectId, user.id(), mode, visible.size(), nodes.size(), relations.size(), linkedWiki.size(),
                fallback.get("matchedCount"), formalCases.size());
        return result;
    }

    private Map<String, Object> node(TestObjectModelRecord item) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", item.id());
        value.put("projectId", item.projectId());
        value.put("sourceProjectId", item.sourceProjectId());
        value.put("scope", item.scope());
        value.put("modelType", item.modelType());
        value.put("name", item.name());
        value.put("description", item.description());
        value.put("propertiesJson", item.propertiesJson());
        value.put("businessDomain", item.businessDomain());
        value.put("sourceType", item.sourceType());
        value.put("sourceRefId", item.sourceRefId());
        return value;
    }

    private int score(TestObjectModelRecord item, List<String> terms) {
        String name = safe(item.name()).toLowerCase();
        String description = (safe(item.description()) + " " + safe(item.businessDomain())
                + " " + safe(item.sourceContext())).toLowerCase();
        int score = 0;
        for (String term : terms) {
            String normalized = term.toLowerCase();
            if (name.contains(normalized)) score += term.length() >= 3 ? 4 : 2;
            else if (description.contains(normalized)) score += term.length() >= 3 ? 2 : 1;
        }
        return score;
    }

    private Map<String, Map<String, Integer>> typeCoverage(List<TestObjectModelRecord> visible,
                                                              List<TestObjectModelRecord> ranked,
                                                              List<Map<String, Object>> selected) {
        Map<String, Map<String, Integer>> coverage = new LinkedHashMap<>();
        for (String type : List.of("ROLE", "FLOW", "STATE", "ACTION", "MODULE", "PAGE", "ASSERTION", "FIELD")) {
            int available = (int) visible.stream().filter(item -> type.equals(item.modelType())).count();
            int matched = (int) ranked.stream().filter(item -> type.equals(item.modelType())).count();
            int included = (int) selected.stream().filter(item -> type.equals(item.get("modelType"))).count();
            coverage.put(type, Map.of("available", available, "matched", matched, "selected", included));
        }
        return coverage;
    }

    static List<String> wikiTerms(List<String> terms, List<TestObjectModelRecord> seeds) {
        return terms.stream()
                .filter(term -> term.length() >= 2 && term.length() <= 4)
                .filter(term -> seeds.isEmpty() || seeds.stream().anyMatch(item ->
                        safeText(item.name()).contains(term) || safeText(item.businessDomain()).contains(term)))
                .sorted(Comparator.<String>comparingInt(term -> (int) seeds.stream().filter(item ->
                                safeText(item.name()).contains(term)).count()).reversed()
                        .thenComparingInt(String::length)
                        .thenComparingInt(terms::indexOf))
                .distinct().limit(3).toList();
    }

    private Map<String, Object> wikiFallback(Long projectId, List<String> queries) {
        Map<String, Object> first = wiki.activeContext(projectId, queries.isEmpty() ? "" : queries.get(0), 8);
        List<Map<String, Object>> searched = new ArrayList<>();
        LinkedHashMap<Object, Map<String, Object>> entries = new LinkedHashMap<>();
        for (int i = 0; i < Math.max(1, queries.size()); i++) {
            String query = queries.isEmpty() ? "" : queries.get(i);
            Map<String, Object> answer = i == 0 ? first : wiki.activeContext(projectId, query, 8);
            searched.add(Map.of("query", query, "matchedCount", answer.getOrDefault("matchedCount", 0)));
            Object rows = answer.get("entries");
            if (rows instanceof List<?> list) for (Object row : list) {
                if (!(row instanceof Map<?, ?> raw) || raw.get("entryId") == null) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                raw.forEach((key, value) -> item.put(String.valueOf(key), value));
                entries.putIfAbsent(item.get("entryId"), item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>(first);
        result.put("entries", entries.values().stream().limit(16).toList());
        result.put("queries", searched);
        result.put("matchedCount", searched.stream().mapToLong(row -> ((Number) row.get("matchedCount")).longValue()).max().orElse(0));
        result.put("truncated", searched.stream().anyMatch(row -> ((Number) row.get("matchedCount")).longValue() > 8));
        return result;
    }

    private static String safeText(String value) { return value == null ? "" : value; }

    static List<String> terms(String text) {
        LinkedHashSet<String> words = new LinkedHashSet<>();
        Matcher matcher = WORDS.matcher(text);
        while (matcher.find() && words.size() < 80) {
            String word = matcher.group();
            if (!Character.UnicodeScript.HAN.equals(Character.UnicodeScript.of(word.codePointAt(0)))) {
                words.add(word.toLowerCase());
                continue;
            }
            for (int size = 2; size <= Math.min(6, word.length() - 1); size++) {
                for (int index = 0; index + size <= word.length() && words.size() < 80; index++) {
                    words.add(word.substring(index, index + size));
                }
            }
            if (word.length() <= 12) words.add(word);
        }
        return List.copyOf(words);
    }

    private String safe(String value) { return value == null ? "" : value; }

    private String likePattern(String term) {
        return "%" + term.replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%";
    }
}

package com.company.aitest.semantic;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 项目语义上下文服务。
 * <p>
 * 从现有 TOM、页面画像、已确认步骤模板、已确认摘要中构建“内部语义包”快照，
 * 供摘要、测试范围分析、用例生成等链路统一召回。
 */
@Service
public class ProjectSemanticContextService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}A-Za-z0-9_/#:-]{2,}");
    private static final Set<String> STOPWORDS = Set.of(
            "页面", "按钮", "点击", "输入", "选择", "提交", "确认", "确定", "操作",
            "流程", "功能", "系统", "列表", "表单", "字段", "模块", "业务", "步骤"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile com.company.aitest.businesspack.BusinessPackService businessPackService;

    public ProjectSemanticContextService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void setBusinessPackService(com.company.aitest.businesspack.BusinessPackService service) {
        this.businessPackService = service;
    }

    public record SemanticSignal(
            String category,
            String title,
            String summary,
            String routeHint,
            double scoreBoost,
            LocalDateTime updatedAt
    ) {
    }

    public record BuildResult(String promptSection, List<SemanticSignal> signals) {
    }

    public record SnapshotRefreshResult(Long packId, int signalCount, int termCount) {
    }

    public BuildResult build(Long projectId, String focusText, List<String> pageUrls) {
        return build(projectId, focusText, pageUrls, 10);
    }

    public BuildResult build(Long projectId, String focusText, List<String> pageUrls, int maxSignals) {
        if (projectId == null) {
            return new BuildResult("", List.of());
        }
        Set<String> keywords = extractKeywords(focusText);
        Set<String> routeHints = normalizeRouteHints(pageUrls);

        List<SemanticSignal> candidates = collectSignals(projectId, routeHints);

        List<SemanticSignal> ranked = rankSignals(candidates, keywords, routeHints, maxSignals);

        // 记录 business_pack 消费（best-effort）
        recordBusinessPackConsumption(projectId, ranked);

        return new BuildResult(buildPromptSection(ranked), ranked);
    }

    private void recordBusinessPackConsumption(Long projectId, List<SemanticSignal> signals) {
        if (businessPackService == null) return;
        long bpCount = signals.stream()
                .filter(s -> s.category() != null && s.category().startsWith("业务包:"))
                .count();
        if (bpCount > 0) {
            try {
                // 找到所有 ACTIVE 的 business_pack，记录消费
                List<com.company.aitest.businesspack.BusinessPackService.BusinessPackRecord> packs =
                        businessPackService.listPacks(projectId, "ACTIVE");
                for (var pack : packs) {
                    businessPackService.recordConsumption(pack.id(), "SEMANTIC_CONTEXT",
                            "build:" + (projectId != null ? projectId : ""), (int) bpCount);
                }
            } catch (Exception ignored) {
                // 消费记录失败不阻塞主链路
            }
        }
    }

    public SnapshotRefreshResult refreshSnapshot(Long projectId) {
        if (projectId == null) {
            return new SnapshotRefreshResult(null, 0, 0);
        }
        List<SemanticSignal> signals = deduplicateSignals(collectSignals(projectId, Set.of()));
        int termCount = (int) signals.stream().filter(signal -> "术语".equals(signal.category())).count();
        LocalDateTime now = LocalDateTime.now();
        String sourceHash = Integer.toHexString(signals.stream()
                .map(signal -> String.join("|",
                        safe(signal.category()),
                        safe(signal.title()),
                        safe(signal.summary()),
                        safe(signal.routeHint())))
                .collect(Collectors.joining("\n"))
                .hashCode());

        jdbcTemplate.update("""
                insert into semantic_pack(project_id, pack_key, scope, status, signal_count, term_count, source_hash, built_at, created_at, updated_at)
                values (?, 'PROJECT_AUTO', 'PROJECT', 'ACTIVE', ?, ?, ?, ?, ?, ?)
                on duplicate key update
                    status = values(status),
                    signal_count = values(signal_count),
                    term_count = values(term_count),
                    source_hash = values(source_hash),
                    built_at = values(built_at),
                    updated_at = values(updated_at)
                """, projectId, signals.size(), termCount, sourceHash, now, now, now);

        Long packId = jdbcTemplate.queryForObject(
                "select id from semantic_pack where project_id = ? and pack_key = 'PROJECT_AUTO'",
                Long.class, projectId);
        if (packId == null) {
            return new SnapshotRefreshResult(null, 0, termCount);
        }

        jdbcTemplate.update("delete from semantic_pack_item where pack_id = ?", packId);
        for (int index = 0; index < signals.size(); index++) {
            SemanticSignal signal = signals.get(index);
            jdbcTemplate.update("""
                    insert into semantic_pack_item(
                        pack_id, project_id, item_no, category, title, summary, route_hint,
                        score_boost, signal_updated_at, keywords_json, source_ref, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    packId, projectId, index + 1, signal.category(), signal.title(), signal.summary(), signal.routeHint(),
                    signal.scoreBoost(), signal.updatedAt(), writeJson(new ArrayList<>(extractKeywords(
                    safe(signal.title()) + " " + safe(signal.summary())))), buildSourceRef(signal), now, now);
        }

        // 联动刷新 business_pack（best-effort，不阻塞主链路）
        if (businessPackService != null) {
            try {
                businessPackService.refreshForProject(projectId);
            } catch (Exception ignored) {
                // business_pack 刷新失败不阻塞 semantic_pack
            }
        }

        return new SnapshotRefreshResult(packId, signals.size(), termCount);
    }

    Set<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (token.length() < 2) {
                continue;
            }
            String normalized = token.toLowerCase(Locale.ROOT);
            if (STOPWORDS.contains(token) || STOPWORDS.contains(normalized)) {
                continue;
            }
            result.add(token);
            result.add(normalized);
        }
        return result;
    }

    List<SemanticSignal> rankSignals(List<SemanticSignal> candidates, Set<String> keywords, Set<String> routeHints, int maxSignals) {
        List<ScoredSignal> scored = new ArrayList<>();
        for (SemanticSignal candidate : candidates) {
            double score = candidate.scoreBoost();
            String haystack = (safe(candidate.title()) + "\n" + safe(candidate.summary())).toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
                    score += 1.6;
                }
            }
            if (candidate.routeHint() != null && routeHints.contains(candidate.routeHint())) {
                score += 3.0;
            }
            if (candidate.updatedAt() != null) {
                score += 0.05;
            }
            if (score > candidate.scoreBoost()) {
                scored.add(new ScoredSignal(candidate, score));
            }
        }

        if (scored.isEmpty()) {
            // 兜底分支也要有 TOM 配额保护
            List<SemanticSignal> fallback = candidates.stream()
                    .sorted(Comparator.comparing(SemanticSignal::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(Math.max(1, Math.min(maxSignals, 6)))
                    .toList();
            return ensureTomQuota(fallback, candidates, maxSignals);
        }

        List<SemanticSignal> ranked = scored.stream()
                .sorted(Comparator
                        .comparingDouble(ScoredSignal::score).reversed()
                        .thenComparing(item -> item.signal().updatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .map(ScoredSignal::signal)
                .distinct()
                .limit(maxSignals)
                .toList();

        return ensureTomQuota(ranked, candidates, maxSignals);
    }

    /**
     * 确保结果列表中至少包含 1 个 TOM:系统 和 1 个 TOM:项目。
     * 如果列表已满但缺少某类 TOM，替换尾部的非 TOM 低优先级项。
     */
    private List<SemanticSignal> ensureTomQuota(List<SemanticSignal> ranked, List<SemanticSignal> allCandidates, int maxSignals) {
        List<SemanticSignal> result = new ArrayList<>(ranked);
        boolean hasSystemTom = result.stream().anyMatch(s -> s.category() != null && s.category().startsWith("TOM:系统"));
        boolean hasProjectTom = result.stream().anyMatch(s -> s.category() != null && s.category().startsWith("TOM:项目"));

        if (hasSystemTom && hasProjectTom) return result;

        // 按 scoreBoost 排序获取候选 TOM
        List<SemanticSignal> tomCandidates = allCandidates.stream()
                .filter(s -> s.category() != null && s.category().startsWith("TOM:"))
                .sorted(Comparator
                        .comparingDouble(SemanticSignal::scoreBoost).reversed()
                        .thenComparing(s -> s.updatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        Set<String> existingTitles = result.stream().map(SemanticSignal::title).collect(java.util.stream.Collectors.toSet());

        for (SemanticSignal tom : tomCandidates) {
            if (hasSystemTom && hasProjectTom) break;
            if (existingTitles.contains(tom.title())) continue;

            boolean needed = (!hasSystemTom && tom.category().startsWith("TOM:系统"))
                    || (!hasProjectTom && tom.category().startsWith("TOM:项目"));
            if (!needed) continue;

            if (result.size() < maxSignals) {
                // 列表未满，直接追加
                result.add(tom);
            } else {
                // 列表已满，替换尾部第一个非 TOM 项
                for (int i = result.size() - 1; i >= 0; i--) {
                    SemanticSignal tail = result.get(i);
                    if (tail.category() == null || !tail.category().startsWith("TOM:")) {
                        result.set(i, tom);
                        break;
                    }
                }
            }

            if (tom.category().startsWith("TOM:系统")) hasSystemTom = true;
            if (tom.category().startsWith("TOM:项目")) hasProjectTom = true;
        }
        return result;
    }

    String buildPromptSection(List<SemanticSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("\n【项目语义包（自动沉淀）】\n");
        builder.append("以下内容由当前项目的 TOM、页面画像、已确认步骤模板、已确认摘要和业务包自动沉淀，仅作为语义理解参考。\n");
        for (SemanticSignal signal : signals) {
            builder.append("- ")
                    .append(signal.category())
                    .append("：")
                    .append(signal.title());
            if (signal.routeHint() != null && !signal.routeHint().isBlank()) {
                builder.append("（").append(signal.routeHint()).append("）");
            }
            if (signal.summary() != null && !signal.summary().isBlank()) {
                builder.append(" — ").append(signal.summary());
            }
            builder.append("\n");
        }
        return builder.toString();
    }

    private List<SemanticSignal> loadTomSignals(Long projectId) {
        return jdbcTemplate.query("""
                select name, description, model_type, scope, source_context, business_domain, updated_at
                from test_object_model
                where status = 'ACTIVE'
                  and ((project_id = ? and scope = 'PROJECT') or scope = 'SYSTEM')
                order by case when project_id = ? then 0 else 1 end, updated_at desc, id desc
                limit 80
                """, (rs, rowNum) -> new SemanticSignal(
                "SYSTEM".equals(rs.getString("scope")) ? "TOM:系统" : "TOM:项目",
                safe(rs.getString("name")),
                compactJoin(
                        prefix("类型", rs.getString("model_type")),
                        prefix("层级", rs.getString("scope")),
                        prefix("领域", rs.getString("business_domain")),
                        clip(firstNonBlank(rs.getString("description"), rs.getString("source_context")), 120)
                ),
                null,
                1.2,
                toLocalDateTime(rs, "updated_at")
        ), projectId, projectId);
    }

    private List<SemanticSignal> loadPageSignals(Long projectId, Set<String> routeHints) {
        List<PageRow> rows = jdbcTemplate.query("""
                select page_label, route_path, field_labels_json, action_labels_json, dialog_titles_json, updated_at, source_key
                from page_scan_profile
                where project_id = ? and status = 'ACTIVE'
                order by case
                    when source_key = 'TRACE_AUTO_SCAN' then 0
                    when source_key = 'URL_IMPORT' then 1
                    else 2 end,
                    updated_at desc, id desc
                limit 60
                """, (rs, rowNum) -> new PageRow(
                safe(rs.getString("page_label")),
                rs.getString("route_path"),
                rs.getString("field_labels_json"),
                rs.getString("action_labels_json"),
                rs.getString("dialog_titles_json"),
                toLocalDateTime(rs, "updated_at")
        ), projectId);

        if (rows.isEmpty()) {
            return List.of();
        }

        List<PageRow> prioritized = new ArrayList<>();
        for (PageRow row : rows) {
            if (row.routePath() != null && routeHints.contains(row.routePath())) {
                prioritized.add(row);
            }
        }
        rows.stream().filter(row -> !prioritized.contains(row)).forEach(prioritized::add);

        return prioritized.stream().limit(20).map(row -> new SemanticSignal(
                "页面画像",
                row.pageLabel(),
                compactJoin(
                        prefixList("字段", parseJsonList(row.fieldLabelsJson())),
                        prefixList("动作", parseJsonList(row.actionLabelsJson())),
                        prefixList("弹窗", parseJsonList(row.dialogTitlesJson()))
                ),
                row.routePath(),
                row.routePath() != null && routeHints.contains(row.routePath()) ? 2.0 : 1.0,
                row.updatedAt()
        )).toList();
    }

    private List<SemanticSignal> loadPatternSignals(Long projectId) {
        return jdbcTemplate.query("""
                select source_text, confirmed_step_text, confirmed_value, operation_type, updated_at
                from trace_correction_candidate
                where project_id = ? and status = 'CONFIRMED' and correction_scope = 'STEP'
                order by confirmed_at desc, id desc
                limit 40
                """, (rs, rowNum) -> {
            String sourceText = firstNonBlank(rs.getString("source_text"), rs.getString("candidate_value"));
            String adopted = firstNonBlank(rs.getString("confirmed_step_text"), rs.getString("confirmed_value"));
            String operationType = safe(rs.getString("operation_type"));
            return new SemanticSignal(
                    "步骤模板",
                    prefix("操作", operationType),
                    clip(compactJoin(
                            prefix("原始", sourceText),
                            prefix("采用", adopted)
                    ), 140),
                    null,
                    1.3,
                    toLocalDateTime(rs, "updated_at")
            );
        }, projectId);
    }

    private List<SemanticSignal> loadSummarySignals(Long projectId) {
        return jdbcTemplate.query("""
                select overview, business_summary, confirmed_at, confidence_label
                from trace_summary
                where project_id = ? and status = 'CONFIRMED'
                order by confirmed_at desc, id desc
                limit 30
                """, (rs, rowNum) -> new SemanticSignal(
                "业务摘要",
                clip(safe(rs.getString("overview")), 60),
                clip(extractBusinessSummary(rs.getString("business_summary")), 140),
                null,
                "HIGH".equals(rs.getString("confidence_label")) ? 1.4 : 1.1,
                toLocalDateTime(rs, "confirmed_at")
        ), projectId);
    }

    private List<SemanticSignal> loadTermSignals(Long projectId) {
        List<String> corpus = new ArrayList<>();

        jdbcTemplate.query("""
                select name, description
                from test_object_model
                where status = 'ACTIVE'
                  and ((project_id = ? and scope = 'PROJECT') or scope = 'SYSTEM')
                limit 120
                """, rs -> {
            corpus.add(safe(rs.getString("name")));
            corpus.add(safe(rs.getString("description")));
        }, projectId);

        jdbcTemplate.query("""
                select page_label, field_labels_json, action_labels_json, dialog_titles_json
                from page_scan_profile
                where project_id = ? and status = 'ACTIVE'
                limit 80
                """, rs -> {
            corpus.add(safe(rs.getString("page_label")));
            corpus.add(String.join(" ", parseJsonList(rs.getString("field_labels_json"))));
            corpus.add(String.join(" ", parseJsonList(rs.getString("action_labels_json"))));
            corpus.add(String.join(" ", parseJsonList(rs.getString("dialog_titles_json"))));
        }, projectId);

        jdbcTemplate.query("""
                select source_text, confirmed_step_text, confirmed_value
                from trace_correction_candidate
                where project_id = ? and status = 'CONFIRMED'
                limit 80
                """, rs -> {
            corpus.add(safe(rs.getString("source_text")));
            corpus.add(safe(firstNonBlank(rs.getString("confirmed_step_text"), rs.getString("confirmed_value"))));
        }, projectId);

        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String line : corpus) {
            for (String token : extractKeywords(line)) {
                String normalized = token.trim();
                if (normalized.length() < 2 || normalized.length() > 20) {
                    continue;
                }
                counts.merge(normalized, 1, Integer::sum);
            }
        }

        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() >= 2)
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(12)
                .map(entry -> new SemanticSignal(
                        "术语",
                        entry.getKey(),
                        "高频术语，命中次数：" + entry.getValue(),
                        null,
                        0.8,
                        null
                ))
                .toList();
    }

    private List<SemanticSignal> collectSignals(Long projectId, Set<String> routeHints) {
        List<SemanticSignal> candidates = new ArrayList<>();
        candidates.addAll(loadTomSignals(projectId));
        candidates.addAll(loadPageSignals(projectId, routeHints == null ? Set.of() : routeHints));
        candidates.addAll(loadPatternSignals(projectId));
        candidates.addAll(loadSummarySignals(projectId));
        candidates.addAll(loadTermSignals(projectId));
        candidates.addAll(loadBusinessPackSignals(projectId));
        return deduplicateSignals(candidates);
    }

    /**
     * 从 ACTIVE business_pack 中加载条目作为语义信号。
     * 这使得 business_pack 的内容能自动流入：
     * - 轨迹清洗（通过 semantic context）
     * - 测试范围分析（通过 MiniTomService.expandKeywordsWithSemanticContext）
     * - 用例生成（通过 GenerationSessionService 的 prompt context）
     */
    private List<SemanticSignal> loadBusinessPackSignals(Long projectId) {
        return jdbcTemplate.query("""
                SELECT bi.item_type, bi.item_key, bi.item_value, bi.confidence, bi.source_type
                FROM business_pack_item bi
                JOIN business_pack bp ON bi.pack_id = bp.id
                WHERE bi.project_id = ? AND bp.status = 'ACTIVE' AND bi.status = 'ACTIVE'
                ORDER BY bi.confidence DESC
                LIMIT 40
                """, (rs, rowNum) -> new SemanticSignal(
                "业务包:" + rs.getString("item_type"),
                safe(rs.getString("item_key")),
                clip(firstNonBlank(rs.getString("item_value"), rs.getString("source_type")), 140),
                null,
                rs.getBigDecimal("confidence").doubleValue(),
                null
        ), projectId);
    }

    private List<SemanticSignal> deduplicateSignals(List<SemanticSignal> candidates) {
        LinkedHashMap<String, SemanticSignal> deduped = new LinkedHashMap<>();
        for (SemanticSignal candidate : candidates) {
            if (candidate == null || candidate.title() == null || candidate.title().isBlank()) {
                continue;
            }
            String key = String.join("|",
                    safe(candidate.category()),
                    safe(candidate.title()),
                    safe(candidate.summary()),
                    safe(candidate.routeHint()));
            deduped.putIfAbsent(key, candidate);
        }
        return new ArrayList<>(deduped.values());
    }

    private Set<String> normalizeRouteHints(List<String> pageUrls) {
        if (pageUrls == null || pageUrls.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (String pageUrl : pageUrls) {
            String normalized = normalizeRoutePath(pageUrl);
            if (normalized != null && !normalized.isBlank()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private String normalizeRoutePath(String pageUrl) {
        if (pageUrl == null || pageUrl.isBlank()) {
            return null;
        }
        try {
            java.net.URI uri = new java.net.URI(pageUrl);
            String path = uri.getPath();
            String query = uri.getQuery();
            String fragment = uri.getFragment();
            String normalizedPath = (path == null || path.isBlank()) ? "/" : path;
            StringBuilder route = new StringBuilder(normalizedPath);
            if (query != null && !query.isBlank()) {
                route.append('?').append(query);
            }
            if (fragment != null && !fragment.isBlank()) {
                route.append('#').append(fragment);
            }
            return route.toString();
        } catch (Exception ignored) {
            return pageUrl;
        }
    }

    private LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toLocalDateTime();
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String extractBusinessSummary(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            List<String> items = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return clip(String.join("；", items), 140);
        } catch (Exception e) {
            return clip(json, 140);
        }
    }

    private String prefix(String label, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return label + "：" + value;
    }

    private String prefixList(String label, List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> normalized = values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .limit(6)
                .toList();
        if (normalized.isEmpty()) {
            return null;
        }
        return label + "：" + String.join("、", normalized);
    }

    private String compactJoin(String... parts) {
        return java.util.Arrays.stream(parts)
                .filter(Objects::nonNull)
                .filter(part -> !part.isBlank())
                .collect(Collectors.joining("；"));
    }

    private String clip(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private String safe(String text) {
        return text == null ? "" : text.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String buildSourceRef(SemanticSignal signal) {
        if (signal == null) {
            return null;
        }
        return signal.category() + ":" + safe(signal.title());
    }

    private record PageRow(
            String pageLabel,
            String routePath,
            String fieldLabelsJson,
            String actionLabelsJson,
            String dialogTitlesJson,
            LocalDateTime updatedAt
    ) {
    }

    private record ScoredSignal(SemanticSignal signal, double score) {
    }
}

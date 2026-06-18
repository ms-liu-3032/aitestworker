package com.company.aitest.minitom;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.company.aitest.common.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mini-TOM 交叉验证服务。
 * <p>
 * 比对手册导入的 TOM 候选与轨迹摘要抽取的 TOM 候选。
 * 结果存储在 test_object_model.cross_validation_json 字段。
 */
@Service
public class MiniTomCrossValidationService {

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MiniTomCrossValidationService(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 对项目下所有 MANUAL_IMPORT CANDIDATE TOM 进行交叉验证。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<CrossValidationResult> crossValidateProject(Long projectId, CurrentUser user) {
        List<TestObjectModelRecord> manualToms = jdbc.sql("""
                        SELECT * FROM test_object_model
                        WHERE project_id = :projectId AND source_type = 'MANUAL_IMPORT' AND status = 'CANDIDATE'
                        """).param("projectId", projectId)
                .query(this::mapTom).list();

        List<TestObjectModelRecord> traceToms = jdbc.sql("""
                        SELECT * FROM test_object_model
                        WHERE project_id = :projectId AND source_type = 'TRACE_SUMMARY' AND status IN ('CANDIDATE', 'ACTIVE')
                        """).param("projectId", projectId)
                .query(this::mapTom).list();

        List<CrossValidationResult> results = new ArrayList<>();
        for (TestObjectModelRecord manualTom : manualToms) {
            CrossValidationResult result = findBestMatch(manualTom, traceToms);
            storeCrossValidation(manualTom.id(), result);
            results.add(result);
        }
        return results;
    }

    private CrossValidationResult findBestMatch(TestObjectModelRecord manual, List<TestObjectModelRecord> traceToms) {
        CrossValidationResult best = null;
        BigDecimal bestScore = BigDecimal.ZERO;

        for (TestObjectModelRecord trace : traceToms) {
            if (!manual.modelType().equals(trace.modelType())) continue;

            BigDecimal score = computeSimilarity(manual, trace);
            if (score.compareTo(bestScore) > 0) {
                bestScore = score;
                best = new CrossValidationResult(
                        manual.id(),
                        score.compareTo(new BigDecimal("0.8")) >= 0 ? "MATCHED" :
                                score.compareTo(new BigDecimal("0.5")) >= 0 ? "PARTIAL_MATCH" : "NO_MATCH",
                        trace.id(), trace.name(), score,
                        buildMatchDetail(manual, trace, score));
            }
        }

        if (best == null) {
            return new CrossValidationResult(
                    manual.id(), "UNIQUE_TO_MANUAL", null, null,
                    BigDecimal.ZERO, "手册独有，轨迹中未发现对应对象");
        }
        return best;
    }

    private BigDecimal computeSimilarity(TestObjectModelRecord a, TestObjectModelRecord b) {
        // 1. 名称包含检查（权重 0.4）
        BigDecimal nameScore = nameSimilarity(a.name(), b.name());

        // 2. 描述关键词重叠（权重 0.3）
        BigDecimal descScore = keywordOverlap(a.description(), b.description());

        // 3. 来源章节重叠（权重 0.3）
        BigDecimal sectionScore = sectionSimilarity(a.sourceSection(), b.sourceSection());

        return nameScore.multiply(new BigDecimal("0.4"))
                .add(descScore.multiply(new BigDecimal("0.3")))
                .add(sectionScore.multiply(new BigDecimal("0.3")))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal nameSimilarity(String a, String b) {
        if (a == null || b == null) return BigDecimal.ZERO;
        String la = a.toLowerCase();
        String lb = b.toLowerCase();
        if (la.equals(lb)) return BigDecimal.ONE;
        if (la.contains(lb) || lb.contains(la)) return new BigDecimal("0.8");

        // Jaccard on character bigrams
        Set<String> bigramsA = bigrams(la);
        Set<String> bigramsB = bigrams(lb);
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) return BigDecimal.ZERO;

        Set<String> intersection = new HashSet<>(bigramsA);
        intersection.retainAll(bigramsB);
        Set<String> union = new HashSet<>(bigramsA);
        union.addAll(bigramsB);

        return BigDecimal.valueOf(intersection.size())
                .divide(BigDecimal.valueOf(union.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal keywordOverlap(String a, String b) {
        if (a == null || b == null) return BigDecimal.ZERO;
        Set<String> kwA = extractKeywords(a);
        Set<String> kwB = extractKeywords(b);
        if (kwA.isEmpty() || kwB.isEmpty()) return BigDecimal.ZERO;

        Set<String> intersection = new HashSet<>(kwA);
        intersection.retainAll(kwB);
        Set<String> union = new HashSet<>(kwA);
        union.addAll(kwB);

        return BigDecimal.valueOf(intersection.size())
                .divide(BigDecimal.valueOf(union.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal sectionSimilarity(String section, String context) {
        if (section == null || context == null) return BigDecimal.ZERO;
        String ls = section.toLowerCase();
        String lc = context.toLowerCase();
        if (ls.contains(lc) || lc.contains(ls)) return new BigDecimal("0.7");
        return BigDecimal.ZERO;
    }

    private Set<String> bigrams(String text) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < text.length() - 1; i++) {
            result.add(text.substring(i, i + 2));
        }
        return result;
    }

    private Set<String> extractKeywords(String text) {
        Set<String> keywords = new HashSet<>();
        if (text == null) return keywords;
        String[] words = text.split("[\\s,;.!?，。；！？、]+");
        for (String word : words) {
            String trimmed = word.trim();
            if (trimmed.length() >= 2 && trimmed.length() <= 20) {
                keywords.add(trimmed.toLowerCase());
            }
        }
        return keywords;
    }

    private String buildMatchDetail(TestObjectModelRecord manual, TestObjectModelRecord trace, BigDecimal score) {
        StringBuilder sb = new StringBuilder();
        sb.append("手册「").append(manual.name()).append("」");
        sb.append(" 与轨迹「").append(trace.name()).append("」");
        sb.append(" 相似度 ").append(score);
        if (score.compareTo(new BigDecimal("0.8")) >= 0) {
            sb.append("，高度匹配");
        } else if (score.compareTo(new BigDecimal("0.5")) >= 0) {
            sb.append("，部分匹配，建议人工确认");
        } else {
            sb.append("，低匹配度");
        }
        return sb.toString();
    }

    private void storeCrossValidation(Long tomId, CrossValidationResult result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            jdbcTemplate.update("""
                    UPDATE test_object_model SET cross_validation_json = ?, updated_at = NOW() WHERE id = ?
                    """, json, tomId);
        } catch (JsonProcessingException e) {
            // 非致命
        }
    }

    private TestObjectModelRecord mapTom(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TestObjectModelRecord(
                rs.getLong("id"), rs.getLong("project_id"),
                rs.getString("scope"),
                getLongNullable(rs, "source_project_id"),
                getLongNullable(rs, "source_project_tom_id"),
                rs.getString("model_type"), rs.getString("name"), rs.getString("description"),
                rs.getString("properties_json"), rs.getString("source_type"),
                getLongNullable(rs, "source_ref_id"), rs.getString("source_context"),
                rs.getBigDecimal("confidence"), rs.getString("status"),
                rs.getBoolean("requires_human_confirm"), rs.getString("validity_label"),
                rs.getLong("created_by"),
                getLongNullable(rs, "confirmed_by"), toLocalDateTime(rs, "confirmed_at"),
                getLongNullable(rs, "rejected_by"), toLocalDateTime(rs, "rejected_at"),
                rs.getString("rejected_reason"),
                getLongNullable(rs, "upgraded_by"), toLocalDateTime(rs, "upgraded_at"),
                toLocalDateTime(rs, "created_at"), toLocalDateTime(rs, "updated_at"),
                rs.getString("business_domain"), rs.getString("priority"),
                rs.getString("source_doc"), rs.getString("source_section"),
                getIntegerNullable(rs, "source_page"),
                rs.getString("evidence_text"), rs.getString("cross_validation_json"),
                rs.getInt("version")
        );
    }

    private Long getLongNullable(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer getIntegerNullable(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private java.time.LocalDateTime toLocalDateTime(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    public record CrossValidationResult(
            Long tomId,
            String matchStatus,
            Long matchedTomId,
            String matchedTomName,
            BigDecimal similarityScore,
            String matchDetail
    ) {
    }
}

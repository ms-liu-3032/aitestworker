package com.company.aitest.minitom;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.company.aitest.semantic.ProjectSemanticContextService;
import com.company.aitest.trace.TraceSummaryRecord;
import com.company.aitest.trace.TraceSummaryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 轨迹摘要上下文构建器。
 * <p>
 * 从已确认的 trace_summary 中，根据需求文本和 TOM 候选做相关性评分，
 * 按 token 预算压缩后输出结构化 prompt 片段，供 TomSemanticMatcher 注入 LLM。
 */
@Component
public class TraceSummaryContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(TraceSummaryContextBuilder.class);

    /** token 预算（约 8000 字符） */
    private static final int TOKEN_BUDGET = 2000;

    /** 字符/token 比率，与 LlmGatewayImpl.estimateTokens() 一致 */
    private static final int CHARS_PER_TOKEN = 4;

    /** 相关性评分权重 */
    private static final double W_OVERVIEW_KEYWORD = 3.0;
    private static final double W_BUSINESS_SUMMARY_KEYWORD = 3.0;
    private static final double W_KEY_STEPS_KEYWORD = 2.0;
    private static final double W_PENDING_CONFIRMATION_KEYWORD = 1.5;
    private static final double W_KEY_API_KEYWORD = 1.0;
    private static final double W_OVERVIEW_TOM_NAME = 2.0;
    private static final double W_BUSINESS_SUMMARY_TOM_NAME = 2.0;
    private static final double W_RECENT_CONFIRM = 0.5;
    private static final double W_HIGH_CONFIDENCE = 0.3;

    private final TraceSummaryService traceSummaryService;
    private final ProjectSemanticContextService semanticContextService;
    private final ObjectMapper objectMapper;

    public TraceSummaryContextBuilder(TraceSummaryService traceSummaryService,
                                      ProjectSemanticContextService semanticContextService) {
        this.traceSummaryService = traceSummaryService;
        this.semanticContextService = semanticContextService;
        this.objectMapper = new ObjectMapper();
    }

    // =====================================================================
    // 公开 API
    // =====================================================================

    public record BuildResult(String promptSection, List<SelectedSummary> selectedSummaries) {}

    public record SelectedSummary(Long summaryId, double score, String hitReasons) {}

    /**
     * 构建轨迹摘要上下文 prompt 片段。
     *
     * @param requirementText 需求文本
     * @param tomCandidates   TOM 候选列表
     * @param projectId       项目 ID
     * @return prompt 片段 + 选中摘要元数据
     */
    public BuildResult build(String requirementText, List<TestObjectModelRecord> tomCandidates, Long projectId) {
        List<TraceSummaryRecord> allConfirmed = traceSummaryService.listConfirmedByProject(projectId);
        if (allConfirmed.isEmpty()) {
            log.info("TraceSummaryContextBuilder: no confirmed summaries for project {}", projectId);
            return new BuildResult("", List.of());
        }

        // 提取关键词
        Set<String> reqKeywords = extractKeywords(requirementText);
        Set<String> tomNames = extractTomNames(tomCandidates);
        String semanticFocus = requirementText + "\n" + String.join("\n", tomNames);
        ProjectSemanticContextService.BuildResult semanticContext =
                semanticContextService.build(projectId, semanticFocus, List.of(), 8);

        // 评分
        List<ScoredSummary> scored = new ArrayList<>();
        for (TraceSummaryRecord summary : allConfirmed) {
            double score = computeScore(summary, reqKeywords, tomNames);
            if (score > 0) {
                scored.add(new ScoredSummary(summary, score, buildHitReasons(summary, reqKeywords, tomNames)));
            }
        }

        if (scored.isEmpty()) {
            log.info("TraceSummaryContextBuilder: no relevant summaries (0/{} scored > 0)", allConfirmed.size());
            return new BuildResult("", List.of());
        }

        // 按分数降序
        scored.sort(Comparator.comparingDouble(ScoredSummary::score).reversed());

        // 按 token 预算填充
        StringBuilder prompt = new StringBuilder();
        prompt.append("\n【系统实际行为（轨迹摘要）】\n");
        prompt.append("以下内容来自当前项目已确认的轨迹摘要，只能作为系统实际行为参考。\n");
        if (!semanticContext.promptSection().isBlank()) {
            prompt.append(semanticContext.promptSection()).append("\n");
        }
        int usedTokens = estimateTokens(prompt.toString());

        List<SelectedSummary> selected = new ArrayList();
        for (ScoredSummary ss : scored) {
            String section = formatSummarySection(ss, reqKeywords);
            int sectionTokens = estimateTokens(section);
            if (usedTokens + sectionTokens > TOKEN_BUDGET) {
                // 尝试截断该摘要以适应剩余预算
                int remainingChars = (TOKEN_BUDGET - usedTokens) * CHARS_PER_TOKEN;
                if (remainingChars > 100) {
                    section = truncateToBudget(section, remainingChars);
                    prompt.append(section);
                    usedTokens += estimateTokens(section);
                    selected.add(new SelectedSummary(ss.summary().id(), ss.score(), ss.hitReasons()));
                }
                break;
            }
            prompt.append(section);
            usedTokens += sectionTokens;
            selected.add(new SelectedSummary(ss.summary().id(), ss.score(), ss.hitReasons()));
        }

        log.info("TraceSummaryContextBuilder: selected {}/{} summaries, budget {} tokens, used {}",
                selected.size(), allConfirmed.size(), TOKEN_BUDGET, usedTokens);
        for (SelectedSummary s : selected) {
            log.info("  #{} score={} reasons={}", s.summaryId(), s.score(), s.hitReasons());
        }

        return new BuildResult(prompt.toString(), selected);
    }

    // =====================================================================
    // 相关性评分
    // =====================================================================

    private double computeScore(TraceSummaryRecord summary, Set<String> reqKeywords, Set<String> tomNames) {
        double score = 0;
        String overview = emptySafe(summary.overview());
        String businessSummary = emptySafe(summary.businessSummary());
        String keySteps = emptySafe(summary.keyStepsJson());
        String pending = emptySafe(summary.pendingConfirmationJson());
        String keyApi = emptySafe(summary.keyApiJson());

        // 需求关键词命中
        score += countKeywordHits(overview, reqKeywords) * W_OVERVIEW_KEYWORD;
        score += countKeywordHits(businessSummary, reqKeywords) * W_BUSINESS_SUMMARY_KEYWORD;
        score += countKeywordHits(keySteps, reqKeywords) * W_KEY_STEPS_KEYWORD;
        score += countKeywordHits(pending, reqKeywords) * W_PENDING_CONFIRMATION_KEYWORD;
        score += countKeywordHits(keyApi, reqKeywords) * W_KEY_API_KEYWORD;

        // TOM 名称命中
        score += countKeywordHits(overview, tomNames) * W_OVERVIEW_TOM_NAME;
        score += countKeywordHits(businessSummary, tomNames) * W_BUSINESS_SUMMARY_TOM_NAME;

        // 最近确认加权
        if (summary.confirmedAt() != null && summary.confirmedAt().isAfter(LocalDateTime.now().minusDays(7))) {
            score += W_RECENT_CONFIRM;
        }

        // 高置信度加权
        if ("HIGH".equals(summary.confidenceLabel())) {
            score += W_HIGH_CONFIDENCE;
        }

        return score;
    }

    private String buildHitReasons(TraceSummaryRecord summary, Set<String> reqKeywords, Set<String> tomNames) {
        List<String> reasons = new ArrayList<>();
        String overview = emptySafe(summary.overview());
        String businessSummary = emptySafe(summary.businessSummary());
        String keySteps = emptySafe(summary.keyStepsJson());
        String pending = emptySafe(summary.pendingConfirmationJson());
        String keyApi = emptySafe(summary.keyApiJson());

        if (countKeywordHits(overview, reqKeywords) > 0) reasons.add("overview+" + W_OVERVIEW_KEYWORD);
        if (countKeywordHits(businessSummary, reqKeywords) > 0) reasons.add("businessSummary+" + W_BUSINESS_SUMMARY_KEYWORD);
        if (countKeywordHits(keySteps, reqKeywords) > 0) reasons.add("keySteps+" + W_KEY_STEPS_KEYWORD);
        if (countKeywordHits(pending, reqKeywords) > 0) reasons.add("pending+" + W_PENDING_CONFIRMATION_KEYWORD);
        if (countKeywordHits(keyApi, reqKeywords) > 0) reasons.add("keyApi+" + W_KEY_API_KEYWORD);
        if (countKeywordHits(overview, tomNames) > 0) reasons.add("overviewTom+" + W_OVERVIEW_TOM_NAME);
        if (countKeywordHits(businessSummary, tomNames) > 0) reasons.add("businessSummaryTom+" + W_BUSINESS_SUMMARY_TOM_NAME);
        if (summary.confirmedAt() != null && summary.confirmedAt().isAfter(LocalDateTime.now().minusDays(7))) {
            reasons.add("recent+" + W_RECENT_CONFIRM);
        }
        if ("HIGH".equals(summary.confidenceLabel())) {
            reasons.add("highConf+" + W_HIGH_CONFIDENCE);
        }
        return String.join(",", reasons);
    }

    private int countKeywordHits(String text, Set<String> keywords) {
        if (text == null || text.isBlank() || keywords.isEmpty()) return 0;
        int hits = 0;
        for (String kw : keywords) {
            if (text.contains(kw)) hits++;
        }
        return hits;
    }

    // =====================================================================
    // 格式化输出
    // =====================================================================

    private String formatSummarySection(ScoredSummary ss, Set<String> reqKeywords) {
        TraceSummaryRecord s = ss.summary();
        StringBuilder sb = new StringBuilder();

        sb.append("\n【轨迹摘要 #%d】（相关度: %.1f）\n".formatted(s.id(), ss.score()));

        // 1. overview - 完整保留
        String overview = emptySafe(s.overview());
        if (!overview.isBlank()) {
            sb.append("概述：").append(overview).append("\n");
        }

        // 2. businessSummary - 完整保留
        String bs = emptySafe(s.businessSummary());
        if (!bs.isBlank()) {
            sb.append("业务摘要：").append(bs).append("\n");
        }

        // 3. keyStepsJson - 只保留命中步骤 + 相邻步骤
        String steps = emptySafe(s.keyStepsJson());
        if (!steps.isBlank()) {
            String filtered = filterKeySteps(steps, reqKeywords);
            if (!filtered.isBlank()) {
                sb.append("关键步骤：\n").append(filtered);
            }
        }

        // 4. keyApiJson - 只保留命中/异常接口
        String api = emptySafe(s.keyApiJson());
        if (!api.isBlank()) {
            String filtered = filterKeyApi(api, reqKeywords);
            if (!filtered.isBlank()) {
                sb.append("关键接口：").append(filtered).append("\n");
            }
        }

        // 5. pendingConfirmationJson - 完整保留（高优先级）
        String pending = emptySafe(s.pendingConfirmationJson());
        if (!pending.isBlank()) {
            sb.append("待确认事项：").append(pending).append("\n");
        }

        // 6. caseGenerationSuggestionJson - 摘要式保留
        String suggestion = emptySafe(s.caseGenerationSuggestionJson());
        if (!suggestion.isBlank()) {
            sb.append("生成建议：").append(truncateToBudget(suggestion, 200)).append("\n");
        }

        // 7. exceptionSummary - 如有异常则保留
        String exception = emptySafe(s.exceptionSummary());
        if (!exception.isBlank()) {
            sb.append("异常摘要：").append(truncateToBudget(exception, 200)).append("\n");
        }

        return sb.toString();
    }

    private String filterKeySteps(String stepsJson, Set<String> reqKeywords) {
        try {
            List<String> steps = objectMapper.readValue(stepsJson, new TypeReference<>() {});
            if (steps == null || steps.isEmpty()) return "";

            // 找到命中的步骤索引
            Set<Integer> hitIndices = new HashSet<>();
            for (int i = 0; i < steps.size(); i++) {
                String step = steps.get(i);
                for (String kw : reqKeywords) {
                    if (step.contains(kw)) {
                        hitIndices.add(i);
                        // 相邻步骤也保留
                        if (i > 0) hitIndices.add(i - 1);
                        if (i < steps.size() - 1) hitIndices.add(i + 1);
                        break;
                    }
                }
            }

            if (hitIndices.isEmpty()) {
                // 无命中，保留前 3 步作为上下文
                int limit = Math.min(steps.size(), 3);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < limit; i++) {
                    sb.append("  - 步骤%d: %s\n".formatted(i + 1, steps.get(i)));
                }
                return sb.toString();
            }

            StringBuilder sb = new StringBuilder();
            List<Integer> sorted = hitIndices.stream().sorted().toList();
            int prev = -2;
            for (int idx : sorted) {
                if (idx - prev > 1 && prev >= 0) {
                    sb.append("  ...\n");
                }
                String marker = "";
                for (String kw : reqKeywords) {
                    if (steps.get(idx).contains(kw)) {
                        marker = "（命中：%s）".formatted(kw);
                        break;
                    }
                }
                sb.append("  - 步骤%d: %s%s\n".formatted(idx + 1, steps.get(idx), marker));
                prev = idx;
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String filterKeyApi(String apiJson, Set<String> reqKeywords) {
        try {
            List<Map<String, Object>> apis = objectMapper.readValue(apiJson, new TypeReference<>() {});
            if (apis == null || apis.isEmpty()) return "";

            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> api : apis) {
                String remark = String.valueOf(api.getOrDefault("remark", ""));
                String url = String.valueOf(api.getOrDefault("url", ""));
                Object statusObj = api.getOrDefault("status", 200);
                int status = statusObj instanceof Number ? ((Number) statusObj).intValue() : 200;

                boolean keep = false;
                // 命中关键词
                for (String kw : reqKeywords) {
                    if (remark.contains(kw) || url.contains(kw)) {
                        keep = true;
                        break;
                    }
                }
                // 异常接口
                if (status >= 400) keep = true;

                if (keep) filtered.add(api);
            }

            if (filtered.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> api : filtered) {
                sb.append("%s %s (%s)".formatted(
                        api.getOrDefault("method", "?"),
                        api.getOrDefault("url", "?"),
                        api.getOrDefault("status", "?")));
                String remark = String.valueOf(api.getOrDefault("remark", ""));
                if (!remark.isBlank() && !"null".equals(remark)) {
                    sb.append(" ").append(remark);
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // =====================================================================
    // 关键词提取
    // =====================================================================

    private Set<String> extractKeywords(String text) {
        Set<String> keywords = new HashSet<>();
        if (text == null || text.isBlank()) return keywords;

        // 按标点和空格分割
        String[] words = text.split("[\\s,;.!?，。；！？、：:（）()\\[\\]【】]+");
        for (String word : words) {
            String trimmed = word.trim();
            if (trimmed.length() >= 2 && trimmed.length() <= 20) {
                keywords.add(trimmed);
            }
        }

        // 中文 2-gram 滑动窗口，提升子串命中率
        String clean = text.replaceAll("[\\s,;.!?，。；！？、：:（）()\\[\\]【】]+", "");
        for (int i = 0; i <= clean.length() - 2; i++) {
            keywords.add(clean.substring(i, i + 2));
        }

        return keywords;
    }

    private Set<String> extractTomNames(List<TestObjectModelRecord> candidates) {
        Set<String> names = new HashSet<>();
        if (candidates == null) return names;
        for (TestObjectModelRecord tom : candidates) {
            if (tom.name() != null && !tom.name().isBlank()) {
                names.add(tom.name());
            }
        }
        return names;
    }

    // =====================================================================
    // 工具方法
    // =====================================================================

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / CHARS_PER_TOKEN);
    }

    private String truncateToBudget(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "...";
    }

    private String emptySafe(String value) {
        return value == null ? "" : value;
    }

    private record ScoredSummary(TraceSummaryRecord summary, double score, String hitReasons) {}
}

package com.company.aitest.loop;

import java.util.ArrayList;
import java.util.List;

import com.company.aitest.common.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LoopIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(LoopIntegrationService.class);

    private final LoopService loopService;

    public LoopIntegrationService(LoopService loopService) {
        this.loopService = loopService;
    }

    public boolean isEnabled() {
        return loopService.isLoopEnabled();
    }

    private void recordAndAutoCluster(Long projectId, String eventType, String sourceStage,
                                       String rawInput, String normalizedIssue, String suggestedAssetType,
                                       String sourceRefsJson, CurrentUser user) {
        loopService.recordEvent(projectId, eventType, sourceStage, rawInput, normalizedIssue,
                suggestedAssetType, sourceRefsJson, user);
        try {
            loopService.autoCluster(projectId);
        } catch (Exception e) {
            log.warn("[Loop] autoCluster failed: {}", e.getMessage());
        }
    }

    /**
     * A. 生成质量回归：用例生成完成后检查质量
     */
    public void onGenerationCompleted(Long projectId, String analysisJson, String testPointsJson,
                                       String draftCasesJson, CurrentUser user) {
        if (!isEnabled()) return;
        log.info("[Loop-A] 生成质量回归检查 projectId={}", projectId);
        List<String> issues = new ArrayList<>();

        if (draftCasesJson != null && draftCasesJson.length() < 50) {
            issues.add("用例草稿内容过短，疑似空生成");
        }
        if (testPointsJson != null && draftCasesJson != null) {
            int tpCount = countOccurrences(testPointsJson, "\"title\"");
            int draftCount = countOccurrences(draftCasesJson, "\"title\"");
            if (tpCount > 0 && draftCount == 0) {
                issues.add("有测试点但未生成对应用例草稿");
            }
        }

        String normalizedIssue = issues.isEmpty() ? "生成质量正常" : String.join("; ", issues);
        recordAndAutoCluster(projectId, "GENERATION_QUALITY", "CASE_GENERATION",
                truncate(draftCasesJson, 2000), normalizedIssue, null, null, user);
    }

    /**
     * B. TOM 使用策略评估：需求分析/用例生成后评估 TOM 命中
     */
    public void onTomUsageEvaluated(Long projectId, String analysisJson, String tomSignalsJson, CurrentUser user) {
        if (!isEnabled()) return;
        log.info("[Loop-B] TOM 策略评估 projectId={}", projectId);
        List<String> issues = new ArrayList<>();

        if (tomSignalsJson == null || tomSignalsJson.isBlank()) {
            issues.add("未使用任何 TOM 信号");
        } else if (tomSignalsJson.length() < 20) {
            issues.add("TOM 信号过少，可能命中不足");
        }

        String normalizedIssue = issues.isEmpty() ? "TOM 使用正常" : String.join("; ", issues);
        recordAndAutoCluster(projectId, "TOM_STRATEGY", "ANALYSIS",
                truncate(tomSignalsJson, 2000), normalizedIssue, null, null, user);
    }

    /**
     * C. 轨迹摘要质量检查：轨迹摘要生成后检查质量
     */
    public void onTraceSummaryCompleted(Long projectId, String summaryJson, String traceRawInput, CurrentUser user) {
        if (!isEnabled()) return;
        log.info("[Loop-C] 轨迹摘要质量检查 projectId={}", projectId);
        List<String> issues = new ArrayList<>();

        if (summaryJson == null || summaryJson.isBlank()) {
            issues.add("摘要为空");
        } else if (summaryJson.length() < 30) {
            issues.add("摘要过短，可能丢失关键步骤");
        }

        String normalizedIssue = issues.isEmpty() ? "轨迹摘要质量正常" : String.join("; ", issues);
        recordAndAutoCluster(projectId, "TRACE_SUMMARY_QUALITY", "TRACE_SUMMARY",
                truncate(traceRawInput, 2000), normalizedIssue, null, null, user);
    }

    /**
     * D. 状态中文化检查：清洗/分析/用例生成后检查中文化
     */
    public void onChineseLocalizationCheck(Long projectId, String content, String sourceStage, CurrentUser user) {
        if (!isEnabled()) return;
        log.info("[Loop-D] 状态中文化检查 projectId={} stage={}", projectId, sourceStage);
        List<String> issues = new ArrayList<>();

        if (content != null) {
            String[] englishPatterns = {"status:", "action:", "page:", "button:", "field:", "label:",
                    "submit", "cancel", "confirm", "loading", "success", "error", "pending"};
            for (String pattern : englishPatterns) {
                if (content.toLowerCase().contains(pattern)) {
                    issues.add("检测到英文技术词: " + pattern);
                    break;
                }
            }
        }

        String normalizedIssue = issues.isEmpty() ? "中文化检查通过" : String.join("; ", issues);
        recordAndAutoCluster(projectId, "LOCALIZATION_CHECK", sourceStage,
                truncate(content, 2000), normalizedIssue, "WIKI", null, user);
    }

    private int countOccurrences(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) { count++; idx += sub.length(); }
        return count;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}

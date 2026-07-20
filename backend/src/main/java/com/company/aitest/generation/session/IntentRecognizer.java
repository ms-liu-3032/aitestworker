package com.company.aitest.generation.session;

import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class IntentRecognizer {

    private static final Set<String> CONFIRM_KEYWORDS = Set.of(
            "没问题", "确认", "按这个来", "ok", "okay", "好的",
            "就这样", "没有问题", "确认分析", "确认无误", "分析正确", "没有修改"
    );
    // Single-char confirm — only for very short messages (<=4 chars)
    private static final Set<String> CONFIRM_SHORT = Set.of("可以", "对", "行", "嗯");

    private static final Set<String> SKIP_KEYWORDS = Set.of(
            "跳过", "直接生成", "直接输出", "不用问了", "按当前内容生成", "跳过确认",
            "不用确认", "跳过反问", "忽略确认"
    );

    private static final Set<String> GENERATE_KEYWORDS = Set.of(
            "生成用例", "输出用例", "生成测试用例", "开始生成", "按这个生成",
            "可以生成了", "生成吧", "输出吧", "请生成", "请输出"
    );

    // Standalone "生成" or "输出" — only for short messages
    private static final Set<String> GENERATE_SHORT = Set.of("生成", "输出");

    private static final Set<String> REANALYZE_KEYWORDS = Set.of(
            "需求分析", "重新分析", "再分析", "更新分析", "重新生成需求分析",
            "重新做需求分析", "重新分析需求", "重新整理分析"
    );

    private static final Set<String> TOM_YES_KEYWORDS = Set.of(
            "使用tom", "结合tom", "用tom", "用模型", "用mini-tom", "使用项目tom",
            "项目tom", "系统tom", "都用", "项目和系统", "tom", "使用tom分析",
            "结合tom分析", "用项目tom", "用系统tom", "项目和系统tom都用"
    );

    private static final Set<String> TOM_NO_KEYWORDS = Set.of(
            "不使用tom", "不用tom", "不要tom", "直接分析", "直接根据", "不需要tom",
            "不结合", "不用结合"
    );

    private static final Set<String> TOM_PROJECT_ONLY_KEYWORDS = Set.of(
            "只用项目tom", "只使用项目tom", "项目tom就行", "只要项目tom", "只用项目"
    );

    public UserIntent recognize(String message, String currentStage) {
        if (message == null || message.isBlank()) return UserIntent.UNKNOWN;
        String raw = message.trim();
        String lower = raw.toLowerCase().replaceAll("[\\s,.，。、!?！？:：;；]", "");
        int len = raw.length();

        // 1. Stage-specific checks first (highest priority)

        // Explicit generation remains a generation command even when a recovered/stale
        // session is parked at ASK_TOM_MODE. The generator itself verifies that an analysis exists.
        if ("ASK_TOM_MODE".equals(currentStage)) {
            if (matchesAny(lower, GENERATE_KEYWORDS)) return UserIntent.GENERATE_CASES;
            if (len <= 4 && matchesAny(lower, GENERATE_SHORT)) return UserIntent.GENERATE_CASES;
            return UserIntent.CHOOSE_TOM_MODE;
        }

        // REQUIREMENT_INPUT: long text = requirement, short = check keywords
        if ("REQUIREMENT_INPUT".equals(currentStage) || currentStage == null) {
            if (len > 15) return UserIntent.SUBMIT_REQUIREMENT;
            // Short messages might be commands
            if (matchesAny(lower, GENERATE_KEYWORDS)) return UserIntent.GENERATE_CASES;
            if (len <= 4 && matchesAny(lower, GENERATE_SHORT)) return UserIntent.GENERATE_CASES;
            return UserIntent.SUBMIT_REQUIREMENT;
        }

        // WAITING_USER_CONFIRMATION: check commands first, then treat as supplement
        if ("WAITING_REQUIREMENT_SCOPE".equals(currentStage)
                || "WAITING_USER_CONFIRMATION".equals(currentStage)
                || "ANALYSIS_READY".equals(currentStage)) {
            // Generate cases
            if (matchesAny(lower, GENERATE_KEYWORDS)) return UserIntent.GENERATE_CASES;
            if (len <= 4 && matchesAny(lower, GENERATE_SHORT)) return UserIntent.GENERATE_CASES;
            // Skip
            if (matchesAny(lower, SKIP_KEYWORDS)) return UserIntent.SKIP_CONFIRMATION;
            // Explicit re-analysis command
            if (isReanalyzeCommand(lower, len)) return UserIntent.REANALYZE_REQUIREMENT;
            // Long or arbitrary text in confirmation stage is more likely a clarification answer.
            if (len > 5) return UserIntent.SUPPLEMENT_REQUIREMENT;
            // Confirm commands only for short/standalone messages, not long answers that merely contain "确认".
            if (isConfirmCommand(lower, len)) return UserIntent.CONFIRM_ANALYSIS;
            // Short clarification answers such as "单选" or "不支持" should still re-analyze.
            return UserIntent.SUPPLEMENT_REQUIREMENT;
        }

        // CASE_READY: user might want to continue
        if ("CASE_READY".equals(currentStage)) {
            if (matchesAny(lower, GENERATE_KEYWORDS)) return UserIntent.GENERATE_CASES;
            if (len > 10) return UserIntent.SUBMIT_REQUIREMENT;
            return UserIntent.UNKNOWN;
        }

        // Other stages: check all keywords, fallback to supplement
        if (matchesAny(lower, GENERATE_KEYWORDS)) return UserIntent.GENERATE_CASES;
        if (len <= 4 && matchesAny(lower, GENERATE_SHORT)) return UserIntent.GENERATE_CASES;
        if (matchesAny(lower, SKIP_KEYWORDS)) return UserIntent.SKIP_CONFIRMATION;
        if (isReanalyzeCommand(lower, len)) return UserIntent.REANALYZE_REQUIREMENT;
        if (isConfirmCommand(lower, len)) return UserIntent.CONFIRM_ANALYSIS;
        if (len <= 4 && matchesAny(lower, CONFIRM_SHORT)) return UserIntent.CONFIRM_ANALYSIS;
        if (len > 10) return UserIntent.SUPPLEMENT_REQUIREMENT;

        return UserIntent.UNKNOWN;
    }

    public String resolveTomMode(String message) {
        String lower = message.trim().toLowerCase().replaceAll("[\\s,.，。、!?！？:：;；]", "");
        if (matchesAny(lower, TOM_NO_KEYWORDS)) return "DIRECT";
        if (matchesAny(lower, TOM_PROJECT_ONLY_KEYWORDS)) return "PROJECT_TOM";
        return "PROJECT_AND_SYSTEM_TOM";
    }

    public boolean isExplicitTomModeChoice(String message) {
        if (message == null || message.isBlank() || message.trim().length() > 30) return false;
        String lower = message.trim().toLowerCase().replaceAll("[\\s,.，。、!?！？:：;；]", "");
        return matchesAny(lower, TOM_NO_KEYWORDS)
                || matchesAny(lower, TOM_PROJECT_ONLY_KEYWORDS)
                || matchesAny(lower, TOM_YES_KEYWORDS);
    }

    private boolean matchesAny(String text, Set<String> keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private boolean isReanalyzeCommand(String text, int rawLength) {
        return REANALYZE_KEYWORDS.contains(text)
                || (rawLength <= 12 && matchesAny(text, REANALYZE_KEYWORDS));
    }

    private boolean isConfirmCommand(String text, int rawLength) {
        if (rawLength <= 4 && matchesAny(text, CONFIRM_SHORT)) {
            return true;
        }
        if (CONFIRM_KEYWORDS.contains(text)) {
            return true;
        }
        return rawLength <= 10 && matchesAny(text, CONFIRM_KEYWORDS);
    }
}

package com.company.aitest.generation;

import java.util.List;
import java.util.Locale;

/** Functional cases still need explicit positive, negative, boundary and combination coverage. */
public final class FunctionalTestDesignPolicy {
    private FunctionalTestDesignPolicy() {
    }

    public static String scenarioType(String pointType) {
        return switch (normalizePointType(pointType)) {
            case "BOUNDARY" -> "BOUNDARY";
            case "BRANCH", "AUTH", "CONCURRENCY" -> "COMBINATION";
            case "EXCEPTION" -> "NEGATIVE";
            case "STATE", "IDEMPOTENT" -> "STATE";
            case "DATA" -> "RECOVERY";
            default -> "POSITIVE";
        };
    }

    public static List<String> designMethods(String pointType, String declaredMethod) {
        List<String> required = switch (normalizePointType(pointType)) {
            case "MAIN_FLOW" -> List.of("场景法");
            case "BRANCH" -> List.of("判定表", "因果图法");
            case "BOUNDARY" -> List.of("等价类划分法", "边界值分析法");
            case "EXCEPTION" -> List.of("等价类划分法", "错误推测法");
            case "STATE" -> List.of("状态迁移法", "因果图法");
            case "DATA" -> List.of("等价类划分法", "数据一致性检查");
            case "AUTH" -> List.of("判定表", "因果图法");
            case "CONCURRENCY" -> List.of("正交实验设计法", "错误推测法");
            case "IDEMPOTENT" -> List.of("状态迁移法", "错误推测法");
            default -> List.of(normalizeDesignMethod(declaredMethod));
        };
        return required.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    public static List<String> coverageRequirements(String pointType) {
        return switch (normalizePointType(pointType)) {
            case "MAIN_FLOW" -> List.of("VALID_FLOW", "EXPECTED_BUSINESS_RESULT");
            case "BRANCH" -> List.of("CONDITION_TRUE", "CONDITION_FALSE", "KEY_COMBINATIONS");
            case "BOUNDARY" -> List.of("VALID_EQUIVALENCE_CLASS", "INVALID_EQUIVALENCE_CLASS",
                    "AT_BOUNDARY", "JUST_INSIDE_BOUNDARY", "JUST_OUTSIDE_BOUNDARY");
            case "EXCEPTION" -> List.of("FAILURE_TRIGGER", "USER_FEEDBACK", "NO_UNEXPECTED_SIDE_EFFECT");
            case "STATE" -> List.of("VALID_TRANSITION", "INVALID_TRANSITION", "RECOVERY_OR_ROLLBACK");
            case "DATA" -> List.of("WRITE_RESULT", "READ_BACK_CONSISTENCY", "FAILURE_CONSISTENCY");
            case "AUTH" -> List.of("AUTHORIZED", "UNAUTHORIZED", "DATA_SCOPE_ISOLATION");
            case "CONCURRENCY" -> List.of("CONCURRENT_CONFLICT", "CONSISTENT_FINAL_RESULT", "NO_DUPLICATE_SIDE_EFFECT");
            case "IDEMPOTENT" -> List.of("FIRST_REQUEST", "REPEATED_REQUEST", "NO_DUPLICATE_SIDE_EFFECT");
            default -> List.of("VALID_FLOW", "EXPECTED_BUSINESS_RESULT");
        };
    }

    public static String normalizeScenarioType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "POSITIVE", "NEGATIVE", "BOUNDARY", "COMBINATION", "STATE", "RECOVERY" -> normalized;
            default -> "POSITIVE";
        };
    }

    private static String normalizePointType(String value) {
        return value == null ? "MAIN_FLOW" : value.trim().toUpperCase(Locale.ROOT);
    }

    public static String normalizeDesignMethod(String value) {
        if (value == null || value.isBlank()) return "场景法";
        return switch (value.trim()) {
            case "等价类", "等价类划分" -> "等价类划分法";
            case "边界值", "边界值分析" -> "边界值分析法";
            case "判定表法" -> "判定表";
            case "状态迁移" -> "状态迁移法";
            case "错误推测" -> "错误推测法";
            case "正交实验", "正交设计" -> "正交实验设计法";
            default -> value.trim();
        };
    }
}

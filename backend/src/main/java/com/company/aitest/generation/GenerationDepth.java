package com.company.aitest.generation;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.company.aitest.common.BusinessException;

public enum GenerationDepth {
    BASIC("基础覆盖", List.of("main_flow", "exception")),
    STANDARD("标准覆盖", List.of("main_flow", "branch", "boundary", "exception", "auth")),
    DEEP("深度覆盖", List.of("main_flow", "branch", "boundary", "exception", "state", "data", "auth", "concurrency", "idempotent"));

    private final String displayName;
    private final List<String> enabledDimensions;

    GenerationDepth(String displayName, List<String> enabledDimensions) {
        this.displayName = displayName;
        this.enabledDimensions = enabledDimensions;
    }

    public static GenerationDepth defaultDepth() {
        return STANDARD;
    }

    public static GenerationDepth resolve(String value) {
        if (value == null || value.isBlank()) {
            return defaultDepth();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "1", "BASIC", "基础", "基础覆盖" -> BASIC;
            case "2", "STANDARD", "标准", "标准覆盖" -> STANDARD;
            case "3", "DEEP", "深度", "深度覆盖" -> DEEP;
            default -> throw new BusinessException("不支持的生成强度: " + value);
        };
    }

    public String displayName() {
        return displayName;
    }

    public List<String> enabledDimensions() {
        return enabledDimensions;
    }

    public Set<String> enabledDimensionSet() {
        return Set.copyOf(enabledDimensions);
    }

    public boolean enables(String dimension) {
        return enabledDimensionSet().contains(dimension);
    }

    public boolean allowsFlowComposition() {
        return this == DEEP;
    }

    public String promptPolicy() {
        return switch (this) {
            case BASIC -> "BASIC 基础覆盖：只生成主流程、关键异常和高风险点；分支、边界、权限、状态、数据、并发、幂等维度必须返回空。";
            case STANDARD -> "STANDARD 标准覆盖：生成主流程、分支、边界、异常、权限；状态、数据、并发、幂等维度必须返回空。";
            case DEEP -> "DEEP 深度覆盖：在标准覆盖基础上加入状态流转、数据一致性、并发、幂等、恢复和真实组合路径。";
        };
    }
}

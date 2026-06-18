package com.company.aitest.tools;

import java.util.Map;

public record ToolGenerateRequest(Integer count, Map<String, String> options) {
    public int normalizedCount() {
        if (count == null) {
            return 1;
        }
        return Math.max(1, Math.min(count, 100));
    }

    public String option(String key, String defaultValue) {
        if (options == null) {
            return defaultValue;
        }
        String value = options.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}

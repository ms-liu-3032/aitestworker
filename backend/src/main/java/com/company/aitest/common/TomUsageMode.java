package com.company.aitest.common;

import java.util.Locale;

/** Explicit TOM scope used by analysis and generation prompts. */
public enum TomUsageMode {
    DIRECT,
    PROJECT_TOM,
    PROJECT_AND_SYSTEM_TOM;

    public boolean usesTom() {
        return this != DIRECT;
    }

    public boolean includesSystemTom() {
        return this == PROJECT_AND_SYSTEM_TOM;
    }

    public static TomUsageMode resolve(String value, boolean legacyUseMiniTom) {
        if (value != null && !value.isBlank()) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Fall through to the legacy flag for old rows and clients.
            }
        }
        return legacyUseMiniTom ? PROJECT_AND_SYSTEM_TOM : DIRECT;
    }

    public static TomUsageMode requireExplicit(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TOM 使用模式不能为空");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}

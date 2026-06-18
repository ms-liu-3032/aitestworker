package com.company.aitest.scan;

import java.nio.file.Path;

public record BuiltinScanSourceDefinition(
        String sourceKey,
        String sourceLabel,
        Path path,
        boolean defaultSelected,
        String sourceType,
        String sourceUrl
) {
    public BuiltinScanSourceDefinition(String sourceKey, String sourceLabel, Path path, boolean defaultSelected) {
        this(sourceKey, sourceLabel, path, defaultSelected, "BUILTIN_JSON", null);
    }
}

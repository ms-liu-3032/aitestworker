package com.company.aitest.preset;

public record DomainPresetDefinition(
        String presetKey,
        String displayName,
        String defaultBusinessDomain,
        String manualDocTitleExample,
        String manualDocTitlePlaceholder,
        String businessDomainPlaceholder
) {
}

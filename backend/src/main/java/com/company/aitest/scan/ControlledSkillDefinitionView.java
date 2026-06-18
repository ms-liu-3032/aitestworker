package com.company.aitest.scan;

public record ControlledSkillDefinitionView(
        String skillCode,
        String displayName,
        String stage,
        String description,
        String defaultSources) {
}

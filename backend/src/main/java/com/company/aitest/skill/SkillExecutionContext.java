package com.company.aitest.skill;

import java.util.List;
import java.util.Map;

public record SkillExecutionContext(
        Long taskId,
        Long projectId,
        Long operatorId,
        Long modelConfigId,
        String promptSnapshot,
        List<String> allowedTools,
        Map<String, Object> runtimeContext
) {
}

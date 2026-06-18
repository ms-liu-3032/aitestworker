package com.company.aitest.trace;

import java.time.LocalDateTime;

public record TestSkillTemplateRecord(Long id, Long projectId, String skillName, String applicableScene,
                                       String flowSteps, String sourceType, Long sourceId, String status,
                                       String deprecatedReason, Integer isVectorized, Long createdBy,
                                       LocalDateTime createdAt, LocalDateTime updatedAt) {
}

package com.company.aitest.trace;

import java.time.LocalDateTime;

public record TestToolTemplateRecord(Long id, Long projectId, String toolName, String operationSteps,
                                      String sourceType, Long sourceId, String status, String deprecatedReason,
                                      Integer isVectorized, Long createdBy, LocalDateTime createdAt,
                                      LocalDateTime updatedAt) {
}

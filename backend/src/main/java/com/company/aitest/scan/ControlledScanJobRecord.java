package com.company.aitest.scan;

import java.time.LocalDateTime;

public record ControlledScanJobRecord(
        Long id,
        Long projectId,
        String jobName,
        String sourceKeysJson,
        Long modelConfigId,
        String status,
        String promptSnapshot,
        String outputSummary,
        Integer profileCount,
        String errorMessage,
        Long createdBy,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

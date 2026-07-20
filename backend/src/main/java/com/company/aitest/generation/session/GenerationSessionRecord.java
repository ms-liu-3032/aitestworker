package com.company.aitest.generation.session;

import java.time.LocalDateTime;

public record GenerationSessionRecord(
        Long id,
        Long projectId,
        String sessionTitle,
        String status,
        String currentStage,
        Long modelConfigId,
        Long promptTemplateId,
        String promptSnapshot,
        boolean useMiniTom,
        String tomMode,
        int latestAnalysisVersion,
        Long executionTaskId,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public GenerationSessionRecord(Long id, Long projectId, String sessionTitle, String status,
                                   String currentStage, Long modelConfigId, Long promptTemplateId,
                                   String promptSnapshot, boolean useMiniTom, int latestAnalysisVersion,
                                   Long executionTaskId, Long createdBy, LocalDateTime createdAt,
                                   LocalDateTime updatedAt) {
        this(id, projectId, sessionTitle, status, currentStage, modelConfigId, promptTemplateId,
                promptSnapshot, useMiniTom,
                useMiniTom ? "PROJECT_AND_SYSTEM_TOM" : "DIRECT",
                latestAnalysisVersion, executionTaskId, createdBy, createdAt, updatedAt);
    }
}

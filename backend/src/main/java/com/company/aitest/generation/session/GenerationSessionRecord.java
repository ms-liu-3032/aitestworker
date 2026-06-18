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
        int latestAnalysisVersion,
        Long executionTaskId,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}

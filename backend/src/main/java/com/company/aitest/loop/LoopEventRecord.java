package com.company.aitest.loop;

import java.time.LocalDateTime;

public record LoopEventRecord(
        Long id,
        Long projectId,
        String eventType,
        String sourceStage,
        String rawInput,
        String normalizedIssue,
        String suggestedAssetType,
        String sourceRefsJson,
        String status,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}

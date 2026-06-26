package com.company.aitest.loop;

import java.time.LocalDateTime;

public record LoopClusterRecord(
        Long id,
        Long projectId,
        String theme,
        int eventCount,
        String suggestedAction,
        String targetAssetType,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}

package com.company.aitest.wiki;

import java.time.LocalDateTime;

public record WikiPackRecord(
        Long id,
        Long projectId,
        String scope,
        String name,
        String status,
        String reviewStatus,
        String trustLevel,
        String sourceType,
        String description,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}

package com.company.aitest.wiki;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record WikiEntryRecord(
        Long id,
        Long packId,
        String entryType,
        String title,
        String content,
        String keywordsJson,
        String sourceRefsJson,
        String reviewStatus,
        BigDecimal confidence,
        String effectiveStatus,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}

package com.company.aitest.scan;

import java.time.LocalDateTime;

public record PageScanProfileRecord(
        Long id,
        Long projectId,
        String sourceKey,
        String sourceLabel,
        String pageLabel,
        String pageUrl,
        String routePath,
        String pageTitle,
        String breadcrumbPath,
        String headingsJson,
        String fieldLabelsJson,
        String actionLabelsJson,
        String dialogTitlesJson,
        String bodyPreview,
        String status,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

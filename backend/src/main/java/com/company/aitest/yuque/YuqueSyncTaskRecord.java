package com.company.aitest.yuque;

import java.time.LocalDateTime;

public record YuqueSyncTaskRecord(Long id, Long projectId, Long yuqueConfigId, String syncMode, String status,
                                  String exportManifestRef, Integer totalDocCount, Integer skippedDocCount,
                                  Integer successDocCount, Integer failedDocCount, String errorMessage,
                                  LocalDateTime startedAt, LocalDateTime finishedAt, Long createdBy,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {
}

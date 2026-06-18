package com.company.aitest.trace;

import java.time.LocalDateTime;

public record BrowserTraceGroupRecord(Long id, Long projectId, Long userId, Long profileId, String groupName,
                                       String description, String status, LocalDateTime startedAt,
                                       LocalDateTime stoppedAt, String timezone,
                                       LocalDateTime createdAt, LocalDateTime updatedAt) {
}

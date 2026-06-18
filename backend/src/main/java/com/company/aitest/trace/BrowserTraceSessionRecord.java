package com.company.aitest.trace;

import java.time.LocalDateTime;

public record BrowserTraceSessionRecord(Long id, Long traceGroupId, Long projectId, Long userId, Long profileId,
                                         String sessionName, String browserType, String browserExecutablePath,
                                         String videoPath, String traceFilePath, String status,
                                         LocalDateTime recordingStartedAtUtc,
                                         LocalDateTime recordingStartedAtLocal, LocalDateTime recordingStoppedAtUtc,
                                         LocalDateTime recordingStoppedAtLocal, String timezone,
                                         String screencastPath,
                                         LocalDateTime screencastStartedAtUtc,
                                         LocalDateTime screencastStoppedAtUtc,
                                         Long screencastDurationMs,
                                         LocalDateTime createdAt, LocalDateTime updatedAt) {
}

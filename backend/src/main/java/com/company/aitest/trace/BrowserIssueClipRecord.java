package com.company.aitest.trace;

import java.time.LocalDateTime;

public record BrowserIssueClipRecord(Long id, Long traceGroupId, Long traceSessionId, Long profileId,
                                      String clipScope, String title, String description, LocalDateTime clipStartAtUtc,
                                      LocalDateTime clipStartAtLocal, LocalDateTime clipEndAtUtc,
                                      LocalDateTime clipEndAtLocal, Long clipStartRelativeMs,
                                      Long clipEndRelativeMs, String timezone, String status, Long createdBy,
                                      String screencastPath, Long screencastClipStartMs, Long screencastClipEndMs,
                                      LocalDateTime createdAt, LocalDateTime updatedAt) {
}

package com.company.aitest.trace;

import java.time.LocalDateTime;

public record BrowserTraceNetworkRecord(Long id, Long traceGroupId, Long traceSessionId, Long profileId,
                                         String url, String method, Integer statusCode, Long durationMs,
                                         Integer failed, String errorMessage, String requestSummary,
                                         String responseSummary, LocalDateTime requestStartedAtUtc,
                                         LocalDateTime requestStartedAtLocal, LocalDateTime responseEndedAtUtc,
                                         LocalDateTime responseEndedAtLocal, String timezone, Long relativeMs,
                                         LocalDateTime createdAt) {
}

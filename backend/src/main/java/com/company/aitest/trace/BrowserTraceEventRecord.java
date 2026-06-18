package com.company.aitest.trace;

import java.time.LocalDateTime;

public record BrowserTraceEventRecord(Long id, Long traceGroupId, Long traceSessionId, Long profileId,
                                       String eventType, String pageUrl, String pageTitle, String elementText,
                                       String elementRole, String selector, String valueSummary,
                                       String screenshotPath, String normalizedLocator, String sectionTitle,
                                       String dialogTitle, String objectLabel, LocalDateTime happenedAtUtc,
                                       LocalDateTime happenedAtLocal, String timezone, Long relativeMs,
                                       LocalDateTime createdAt) {
}

package com.company.aitest.trace;

import java.time.LocalDateTime;

public record TraceGeneratedCaseRecord(Long id, Long projectId, Long userId, Long traceGroupId,
                                        Long traceSessionId, Long issueClipId, String caseType, String caseTitle,
                                        String moduleName, String precondition, String steps, String expectedResult,
                                        String priority, String caseScope, String caseStatus, String sourceRefsJson,
                                        Long modelConfigId, String promptSnapshot, LocalDateTime createdAt,
                                        LocalDateTime updatedAt) {
}

package com.company.aitest.trace;

import java.time.LocalDateTime;

public record BrowserProfileOperationRecord(Long id, Long profileId, Long projectId, Long userId,
                                             String operationType, String operationDetail, LocalDateTime createdAt) {
}

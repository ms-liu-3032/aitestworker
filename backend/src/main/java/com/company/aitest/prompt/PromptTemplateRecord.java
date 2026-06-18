package com.company.aitest.prompt;

import java.time.LocalDateTime;

public record PromptTemplateRecord(Long id, String promptName, String promptType, String scope, String content,
                                   String status, Integer version, String reviewStatus,
                                   Long contributorUserId, String contributorUsername,
                                   LocalDateTime deprecatedAt, Long deprecatedBy, String deprecatedReason,
                                   LocalDateTime createdAt, LocalDateTime updatedAt) {
}

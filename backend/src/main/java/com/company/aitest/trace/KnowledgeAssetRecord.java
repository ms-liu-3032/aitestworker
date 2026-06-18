package com.company.aitest.trace;

import java.time.LocalDateTime;

public record KnowledgeAssetRecord(Long id, Long projectId, String assetType, String assetRefType, Long assetRefId,
                                    String title, String content, String status, String visibility, Long createdBy,
                                    Long deprecatedBy, LocalDateTime deprecatedAt, String deprecatedReason,
                                    String deprecatedNote, Integer isVectorized, String vectorId,
                                    LocalDateTime createdAt, LocalDateTime updatedAt) {
}

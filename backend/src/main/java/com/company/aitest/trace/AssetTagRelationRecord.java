package com.company.aitest.trace;

import java.time.LocalDateTime;

public record AssetTagRelationRecord(Long id, Long projectId, Long tagId, String targetType, Long targetId,
                                      Long createdBy, LocalDateTime createdAt) {
}

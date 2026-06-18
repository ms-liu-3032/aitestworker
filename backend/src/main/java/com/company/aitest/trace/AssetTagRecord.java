package com.company.aitest.trace;

import java.time.LocalDateTime;

public record AssetTagRecord(Long id, Long projectId, String tagName, String tagType, Long createdBy,
                              LocalDateTime createdAt) {
}

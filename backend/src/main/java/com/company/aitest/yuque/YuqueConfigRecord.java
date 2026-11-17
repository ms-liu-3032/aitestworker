package com.company.aitest.yuque;

import java.time.LocalDateTime;

public record YuqueConfigRecord(Long id, Long projectId, String configName, String host, String authMode,
                                String username, Integer saveCredential, String repoScope, String exporterType,
                                Integer enabled, Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) {
}

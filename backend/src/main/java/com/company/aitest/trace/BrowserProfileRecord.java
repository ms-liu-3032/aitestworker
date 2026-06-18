package com.company.aitest.trace;

import java.time.LocalDateTime;

public record BrowserProfileRecord(Long id, Long projectId, Long userId, String profileName, String targetHost,
                                    String accountLabel, String roleLabel, String username, String passwordCipher,
                                    String storageStatePath, String status,
                                    LocalDateTime lastUsedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
}

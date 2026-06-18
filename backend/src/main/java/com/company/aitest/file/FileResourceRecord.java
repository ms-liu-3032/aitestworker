package com.company.aitest.file;

import java.time.LocalDateTime;

public record FileResourceRecord(Long id, Long projectId, String fileName, String fileType, String storageType,
                                 String storagePath, String contentHash, Long createdBy, LocalDateTime createdAt) {
}

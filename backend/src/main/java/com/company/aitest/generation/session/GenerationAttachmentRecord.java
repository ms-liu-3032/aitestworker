package com.company.aitest.generation.session;

import java.time.LocalDateTime;

public record GenerationAttachmentRecord(
        Long id,
        Long sessionId,
        Long messageId,
        String fileName,
        String fileType,
        long fileSize,
        String storagePath,
        String contentHash,
        String parseStatus,
        String parsedContent,
        String parseError,
        String visionResult,
        Long createdBy,
        LocalDateTime createdAt
) {}

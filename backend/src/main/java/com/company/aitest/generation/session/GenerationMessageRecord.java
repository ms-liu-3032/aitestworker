package com.company.aitest.generation.session;

import java.time.LocalDateTime;

public record GenerationMessageRecord(
        Long id,
        Long sessionId,
        String role,
        String content,
        String structuredPayload,
        int analysisVersion,
        String stage,
        LocalDateTime createdAt
) {}

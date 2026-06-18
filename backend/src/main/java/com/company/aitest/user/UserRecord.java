package com.company.aitest.user;

import java.time.LocalDateTime;

public record UserRecord(
        Long id,
        String username,
        String displayName,
        String roleCode,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

package com.company.aitest.project;

import java.time.LocalDateTime;

public record ProjectRecord(Long id, String projectName, String description, String status, Long createdBy,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
}

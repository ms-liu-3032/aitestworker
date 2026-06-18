package com.company.aitest.model;

import java.time.LocalDateTime;

public record ModelConfigRecord(Long id, String configName, String provider, String modelName, String endpoint,
                                String status, Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) {
}

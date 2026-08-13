package com.company.aitest.model;

import java.time.LocalDateTime;

public record ModelConfigRecord(Long id, String configName, String provider, String modelName, String endpoint,
                                String status, Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt,
                                Boolean visionEnabled) {

    public ModelConfigRecord(Long id, String configName, String provider, String modelName, String endpoint,
                             String status, Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, configName, provider, modelName, endpoint, status, createdBy, createdAt, updatedAt, null);
    }
}

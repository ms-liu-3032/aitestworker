package com.company.aitest.trace;

import java.time.LocalDateTime;

public record WorkerDeviceRecord(Long id, Long userId, String deviceName, String platform, String arch,
                                  String workerVersion, String protocolVersion, String bindStatus,
                                  String workerTokenHash, LocalDateTime lastSeenAt, LocalDateTime createdAt,
                                  LocalDateTime updatedAt) {
}

package com.company.aitest.minitom;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 测试对象模型关系记录。
 * 连接两个 TOM 实体。
 */
public record TestObjectModelRelationRecord(
        Long id,
        Long projectId,
        Long fromModelId,
        String relationType,
        Long toModelId,
        BigDecimal confidence,
        String status,
        String sourceType,
        Long sourceRefId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

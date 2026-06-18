package com.company.aitest.minitom;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 测试对象模型记录。
 * 默认 status = CANDIDATE，人工确认后变 ACTIVE。
 */
public record TestObjectModelRecord(
        Long id,
        Long projectId,
        String scope,
        Long sourceProjectId,
        Long sourceProjectTomId,
        String modelType,
        String name,
        String description,
        String propertiesJson,
        String sourceType,
        Long sourceRefId,
        String sourceContext,
        BigDecimal confidence,
        String status,
        boolean requiresHumanConfirm,
        String validityLabel,
        Long createdBy,
        Long confirmedBy,
        LocalDateTime confirmedAt,
        Long rejectedBy,
        LocalDateTime rejectedAt,
        String rejectedReason,
        Long upgradedBy,
        LocalDateTime upgradedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        // --- 手册导入扩展字段 ---
        String businessDomain,
        String priority,
        String sourceDoc,
        String sourceSection,
        Integer sourcePage,
        String evidenceText,
        String crossValidationJson,
        Integer version
) {
}

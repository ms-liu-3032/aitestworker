package com.company.aitest.trace;

import java.time.LocalDateTime;

/**
 * trace_summary 正式承载层记录。
 * <p>
 * 对应 V8 扩展后的 trace_summary 表结构。
 * 旧字段（summary_text, scope, review_status, trust_level）保留兼容。
 *
 * @see docs/handover/10_轨迹摘要文本化P0设计与实现方案.md §4
 */
public record TraceSummaryRecord(
        Long id,
        Long projectId,
        Long traceGroupId,
        Long traceSessionId,
        Long issueClipId,
        String summaryScope,
        String overview,
        String businessSummary,
        String keyStepsJson,
        String keyApiJson,
        String exceptionSummary,
        String caseGenerationSuggestionJson,
        String rawInputSnapshot,
        String llmOutputSnapshot,
        Long modelConfigId,
        Long promptSnapshotId,
        Long contextManifestId,
        Long llmInvocationLogId,
        String status,
        String validityLabel,
        Long createdBy,
        Long confirmedBy,
        LocalDateTime confirmedAt,
        Long rejectedBy,
        LocalDateTime rejectedAt,
        String rejectedReason,
        Long deprecatedBy,
        LocalDateTime deprecatedAt,
        String deprecatedReason,
        String pendingConfirmationJson,
        String confidenceLabel,
        String summaryText,
        String scope,
        String reviewStatus,
        String trustLevel,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer version
) {
}

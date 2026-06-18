package com.company.aitest.trace;

import java.time.LocalDateTime;

/**
 * trace_correction_candidate 正式承载层记录。
 * <p>
 * 轨迹定位与语义修正建议候选层。
 * 只有 status = CONFIRMED 的修正建议才允许参与摘要生成、用例草稿和 Mini-TOM 候选抽取。
 *
 * @see docs/handover/11_轨迹定位与语义修正建议设计方案.md §6
 */
public record TraceCorrectionCandidateRecord(
        Long id,
        Long projectId,
        Long traceGroupId,
        Long traceSessionId,
        Long issueClipId,
        Long traceEventId,
        Long summaryId,

        String correctionType,
        String sourceText,
        String candidateValue,
        String confirmedValue,
        String candidateReason,
        String confidenceLabel,

        String status,

        Integer stepNo,
        String correctionScope,
        String operationType,
        String candidateStepText,
        String confirmedStepText,
        Integer relatedStepNo,

        String rawContextSnapshot,
        Long promptSnapshotId,
        Long contextManifestId,
        Long llmInvocationLogId,

        Long createdBy,
        Long confirmedBy,
        LocalDateTime confirmedAt,
        Long rejectedBy,
        LocalDateTime rejectedAt,
        String rejectedReason,

        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

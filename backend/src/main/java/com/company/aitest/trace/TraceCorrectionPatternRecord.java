package com.company.aitest.trace;

import java.time.LocalDateTime;

/**
 * trace_correction_pattern 轻量模式记忆记录。
 * <p>
 * 基于已确认修正结果沉淀的模式，用于提升后续候选建议质量。
 * 不自动回写正式资产，只作为候选生成的参考输入。
 *
 * @see docs/handover/11_轨迹定位与语义修正建议设计方案.md §3.7
 */
public record TraceCorrectionPatternRecord(
        Long id,
        Long projectId,

        String pageUrlPattern,
        String pageTitleKeyword,
        String elementTextPattern,
        String elementRole,
        String dialogTitleKeyword,
        String sectionTitleKeyword,

        String correctionType,
        String operationType,
        String fromText,
        String toText,

        Integer confirmedCount,
        LocalDateTime lastConfirmedAt,

        String sourceCorrectionIds,

        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

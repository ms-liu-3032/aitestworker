package com.company.aitest.llm.gateway.audit;

/**
 * 一次 LLM 调用的可写入日志快照。
 * <p>
 * 字段对齐 {@code llm_invocation_log} 表。入参/出参快照使用文件存储引用（ref），
 * 留待后续 Sprint 把大文本走 {@code file_resource} 后再补；M1 阶段直接在 DB 存 inline。
 */
public record LlmInvocationLogEntry(
        String requestId,
        Long userId,
        Long projectId,
        Long taskId,
        String taskType,
        String stage,
        Long modelConfigId,
        Long promptTemplateId,
        Integer promptVersion,
        Long promptSnapshotId,
        String inputSnapshotRef,
        String outputSnapshotRef,
        Long contextManifestId,
        int tokenInput,
        int tokenOutput,
        String status,
        String errorCode,
        String errorMessage,
        long durationMs
) {
}

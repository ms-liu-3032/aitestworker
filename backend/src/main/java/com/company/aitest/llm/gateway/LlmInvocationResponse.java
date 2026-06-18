package com.company.aitest.llm.gateway;

/**
 * LLM Gateway 调用结果。
 * <p>
 * 任何被 Gateway 处理过的请求（无论成功/失败/被守卫拦截）都会有一条对应的
 * llm_invocation_log 记录，{@code invocationLogId} 是该记录主键。
 * 失败场景 {@code content} 可能为空但 {@code status} 一定有意义。
 */
public record LlmInvocationResponse(
        String requestId,
        String content,
        int tokenInput,
        int tokenOutput,
        long durationMs,
        Long invocationLogId,
        Long contextManifestId,
        Long promptSnapshotId,
        LlmInvocationStatus status,
        String errorCode,
        String errorMessage
) {
}

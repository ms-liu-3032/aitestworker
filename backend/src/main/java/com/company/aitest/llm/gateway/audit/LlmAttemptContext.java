package com.company.aitest.llm.gateway.audit;

import com.company.aitest.llm.gateway.LlmInvocationRequest;

/**
 * 当前线程内的 LLM provider attempt 观测上下文。
 * <p>
 * Gateway 负责绑定业务上下文，Http adapter 只上报每次 provider 尝试的结果。
 * 这样不需要改动 {@code LlmAdapter} 接口，也避免业务服务直连 provider。
 */
public final class LlmAttemptContext {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private LlmAttemptContext() {
    }

    public static void bind(String requestId, LlmInvocationRequest request,
                            Long promptSnapshotId, Long contextManifestId) {
        CURRENT.set(new Context(
                requestId,
                request.userId(),
                request.projectId(),
                request.taskId(),
                request.taskType(),
                request.stage() == null ? "UNKNOWN" : request.stage().name(),
                request.modelConfigId(),
                request.promptTemplateId(),
                request.promptVersion(),
                promptSnapshotId,
                contextManifestId
        ));
    }

    public static Context current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public record Context(
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
            Long contextManifestId
    ) {
    }
}

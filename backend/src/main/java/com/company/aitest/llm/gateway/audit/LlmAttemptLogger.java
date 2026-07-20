package com.company.aitest.llm.gateway.audit;

import com.company.aitest.llm.gateway.LlmErrorCode;
import org.springframework.stereotype.Component;

/**
 * 记录 provider HTTP 层每一次 attempt。
 * <p>
 * 最终业务结果仍由 {@link LlmInvocationLogger} 写一条主 invocation log；
 * attempt log 用独立 request_id 后缀，便于排查第几次尝试超时、限流或 5xx。
 */
@Component
public class LlmAttemptLogger {
    private static final int SNAPSHOT_LIMIT = 1000;

    private final LlmInvocationLogger invocationLogger;

    public LlmAttemptLogger(LlmInvocationLogger invocationLogger) {
        this.invocationLogger = invocationLogger;
    }

    public void record(int attemptIndex,
                       String status,
                       LlmErrorCode errorCode,
                       String errorMessage,
                       String rawOutputSnapshot,
                       long durationMs) {
        LlmAttemptContext.Context ctx = LlmAttemptContext.current();
        if (ctx == null) {
            return;
        }
        invocationLogger.record(new LlmInvocationLogEntry(
                attemptRequestId(ctx.requestId(), attemptIndex),
                ctx.userId(),
                ctx.projectId(),
                ctx.taskId(),
                ctx.taskType(),
                ctx.stage(),
                ctx.modelConfigId(),
                ctx.promptTemplateId(),
                ctx.promptVersion(),
                ctx.promptSnapshotId(),
                null,
                null,
                attemptIndex,
                snapshot(rawOutputSnapshot),
                ctx.contextManifestId(),
                0,
                0,
                status,
                errorCode == null ? null : errorCode.name(),
                errorMessage,
                durationMs
        ));
    }

    private String attemptRequestId(String requestId, int attemptIndex) {
        String suffix = "#a" + attemptIndex;
        String base = requestId == null || requestId.isBlank() ? "unknown" : requestId;
        int maxBaseLength = Math.max(1, 64 - suffix.length());
        if (base.length() > maxBaseLength) {
            base = base.substring(0, maxBaseLength);
        }
        return base + suffix;
    }

    private String snapshot(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String compact = raw.replaceAll("\\s+", " ").trim();
        return compact.length() <= SNAPSHOT_LIMIT ? compact : compact.substring(0, SNAPSHOT_LIMIT);
    }
}

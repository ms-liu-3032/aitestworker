package com.company.aitest.llm.gateway;

import java.util.Map;

/**
 * LLM Gateway 调用入参。
 * <p>
 * 必填：userId、projectId、taskId、stage、modelConfigId、promptTemplateId。
 * variables 用于 prompt 模板渲染（可为空 Map，但不允许 null）。
 * <p>
 * caller 应当负责生成 {@code requestId}（UUID），便于跨服务追踪。
 * 若 caller 没传，{@link LlmGateway} 实现会兜底生成。
 */
public record LlmInvocationRequest(
        String requestId,
        Long userId,
        Long projectId,
        Long taskId,
        String taskType,
        LlmStage stage,
        Long modelConfigId,
        Long promptTemplateId,
        Integer promptVersion,
        Map<String, Object> variables,
        String systemPromptOverride,
        String userPromptOverride,
        Long traceGroupId,
        Integer maxTokens
) {
    public LlmInvocationRequest {
        if (variables == null) {
            variables = Map.of();
        }
    }

    public LlmInvocationRequest(String requestId, Long userId, Long projectId, Long taskId, String taskType,
                                 LlmStage stage, Long modelConfigId, Long promptTemplateId, Integer promptVersion,
                                 Map<String, Object> variables, String systemPromptOverride, String userPromptOverride,
                                 Long traceGroupId) {
        this(requestId, userId, projectId, taskId, taskType, stage, modelConfigId, promptTemplateId, promptVersion,
             variables, systemPromptOverride, userPromptOverride, traceGroupId, null);
    }
}

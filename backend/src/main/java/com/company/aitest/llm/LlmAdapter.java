package com.company.aitest.llm;

public interface LlmAdapter {
    String complete(CompletionRequest request);

    default CompletionResponse completeWithUsage(CompletionRequest request) {
        return new CompletionResponse(complete(request), null, null);
    }

    record CompletionRequest(Long modelConfigId, String systemPrompt, String userPrompt, Integer maxTokens) {
        public CompletionRequest(Long modelConfigId, String systemPrompt, String userPrompt) {
            this(modelConfigId, systemPrompt, userPrompt, null);
        }
    }

    record CompletionResponse(String content, Integer promptTokens, Integer completionTokens,
                              Integer cachedPromptTokens) {
        public CompletionResponse(String content, Integer promptTokens, Integer completionTokens) {
            this(content, promptTokens, completionTokens, null);
        }
    }
}

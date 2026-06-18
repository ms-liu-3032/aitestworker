package com.company.aitest.llm;

public interface LlmAdapter {
    String complete(CompletionRequest request);

    record CompletionRequest(Long modelConfigId, String systemPrompt, String userPrompt, Integer maxTokens) {
        public CompletionRequest(Long modelConfigId, String systemPrompt, String userPrompt) {
            this(modelConfigId, systemPrompt, userPrompt, null);
        }
    }
}

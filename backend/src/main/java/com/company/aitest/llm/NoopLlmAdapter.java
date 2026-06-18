package com.company.aitest.llm;

public class NoopLlmAdapter implements LlmAdapter {
    @Override
    public String complete(CompletionRequest request) {
        return "{}";
    }
}

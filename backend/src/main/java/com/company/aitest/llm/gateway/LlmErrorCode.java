package com.company.aitest.llm.gateway;

public enum LlmErrorCode {
    TIMEOUT,
    RATE_LIMITED,
    PROVIDER_ERROR,
    AUTH_ERROR,
    INSUFFICIENT_BALANCE,
    MODEL_NOT_FOUND,
    CONTEXT_TOO_LONG,
    /** The provider completed its reasoning channel but never emitted a usable final answer. */
    REASONING_EXHAUSTED,
    OUTPUT_PARSE_ERROR,
    INVALID_REQUEST,
    UNKNOWN_ERROR
}

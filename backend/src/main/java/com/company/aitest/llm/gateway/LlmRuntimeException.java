package com.company.aitest.llm.gateway;

import com.company.aitest.common.BusinessException;

public class LlmRuntimeException extends BusinessException {
    private final LlmErrorCode errorCode;

    public LlmRuntimeException(LlmErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode == null ? LlmErrorCode.UNKNOWN_ERROR : errorCode;
    }

    public LlmErrorCode errorCode() {
        return errorCode;
    }
}

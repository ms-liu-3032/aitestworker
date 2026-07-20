package com.company.aitest.llm.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonOutputParserTest {

    @Test
    void extractsJsonObjectFromMarkdownFence() {
        String raw = """
                ```json
                {"cases":[{"caseTitle":"登录成功"}]}
                ```
                """;

        String json = JsonOutputParser.extractJson(raw);

        assertTrue(json.contains("\"cases\""));
        assertTrue(json.contains("登录成功"));
    }

    @Test
    void extractsJsonArrayWrappedInText() {
        String raw = "下面是结果：[{\"pointContent\":\"边界值\"}]，请查收";

        String json = JsonOutputParser.extractJson(raw);

        assertEquals("[{\"pointContent\":\"边界值\"}]", json);
    }

    @Test
    void failsWithOutputParseErrorWhenJsonInvalid() {
        LlmRuntimeException ex = assertThrows(LlmRuntimeException.class,
                () -> JsonOutputParser.extractJson("不是 JSON"));

        assertEquals(LlmErrorCode.OUTPUT_PARSE_ERROR, ex.errorCode());
    }
}

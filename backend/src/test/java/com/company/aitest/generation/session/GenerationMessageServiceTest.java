package com.company.aitest.generation.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class GenerationMessageServiceTest {

    @Test
    void normalizeStructuredPayloadReturnsCanonicalJsonForValidObject() {
        assertEquals("{\"analysis\":{\"ok\":true}}",
                GenerationMessageService.normalizeStructuredPayload("""
                        {
                          "analysis": {
                            "ok": true
                          }
                        }
                        """));
    }

    @Test
    void normalizeStructuredPayloadReturnsCanonicalJsonForValidArray() {
        assertEquals("[{\"title\":\"t1\"}]",
                GenerationMessageService.normalizeStructuredPayload("""
                        [
                          { "title": "t1" }
                        ]
                        """));
    }

    @Test
    void normalizeStructuredPayloadReturnsNullForPlainText() {
        assertNull(GenerationMessageService.normalizeStructuredPayload("not-json"));
    }

    @Test
    void normalizeStructuredPayloadReturnsNullForBlankInput() {
        assertNull(GenerationMessageService.normalizeStructuredPayload("   "));
        assertNull(GenerationMessageService.normalizeStructuredPayload(null));
    }
}

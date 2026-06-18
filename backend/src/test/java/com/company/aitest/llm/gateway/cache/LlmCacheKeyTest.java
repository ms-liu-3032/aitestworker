package com.company.aitest.llm.gateway.cache;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LlmCacheKeyTest {

    @Test
    void ctxKey() {
        String k = LlmCacheKey.ctx(10L, 20L, 30L, "TEST_CASE_GEN");
        assertEquals("aitest:llm:ctx:10:20:30:TEST_CASE_GEN", k);
    }

    @Test
    void ragKeyIsHashed() {
        String k = LlmCacheKey.rag(10L, 20L, "PROJECT", "登录失败");
        assertTrue(k.startsWith("aitest:llm:rag:10:20:PROJECT:"));
        // 哈希取前 16 hex 字符
        assertEquals(16, k.substring(k.lastIndexOf(':') + 1).length());
    }

    @Test
    void promptKey() {
        String k = LlmCacheKey.prompt(7L, 3, "abc123");
        assertEquals("aitest:llm:prompt:7:3:abc123", k);
    }

    @Test
    void quotaKeyByDate() {
        String k = LlmCacheKey.userQuota(20L, LocalDate.of(2026, 5, 22));
        assertEquals("aitest:llm:quota:20:2026-05-22", k);
    }

    @Test
    void rejectsNullScope() {
        assertThrows(NullPointerException.class,
                () -> LlmCacheKey.ctx(1L, 2L, 3L, null));
        assertThrows(IllegalArgumentException.class,
                () -> LlmCacheKey.ctx(null, 2L, 3L, "S"));
        assertThrows(IllegalArgumentException.class,
                () -> LlmCacheKey.userQuota(null, LocalDate.now()));
    }
}

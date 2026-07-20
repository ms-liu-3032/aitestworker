package com.company.aitest.llm.gateway.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class LlmInvocationLoggerIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private LlmInvocationLogger logger;

    @Test
    void recordWritesProviderModelAndRawOutputSnapshot() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 30, 15, 20);
        jdbcTemplate.update("""
                insert into model_config(config_name, provider, model_name, endpoint, api_key_encrypted, status,
                  created_by, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, "logger-test", "OTHER", "gpt-test", "https://example.test", "secret", 1L, now, now);
        Long modelConfigId = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        String requestId = "logger-test-" + UUID.randomUUID();

        Long id = logger.record(new LlmInvocationLogEntry(
                requestId,
                1L,
                2L,
                3L,
                "GENERATION",
                "TEST_CASE_GEN",
                modelConfigId,
                null,
                null,
                null,
                null,
                null,
                0,
                "raw output snapshot",
                null,
                10,
                20,
                "OK",
                null,
                null,
                123L
        ));

        assertNotNull(id);
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select provider, model_name, retry_index, raw_output_snapshot
                from llm_invocation_log
                where request_id = ?
                """, requestId);
        assertEquals("OTHER", row.get("provider"));
        assertEquals("gpt-test", row.get("model_name"));
        assertEquals(0, ((Number) row.get("retry_index")).intValue());
        assertEquals("raw output snapshot", row.get("raw_output_snapshot"));
    }
}

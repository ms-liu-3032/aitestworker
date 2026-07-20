package com.company.aitest.llm;

import com.company.aitest.common.TimeProvider;
import com.company.aitest.llm.gateway.LlmInvocationRequest;
import com.company.aitest.llm.gateway.LlmStage;
import com.company.aitest.llm.gateway.audit.LlmAttemptContext;
import com.company.aitest.llm.gateway.audit.LlmAttemptLogger;
import com.company.aitest.llm.gateway.audit.LlmInvocationLogEntry;
import com.company.aitest.llm.gateway.audit.LlmInvocationLogger;
import com.company.aitest.model.ModelConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpLlmAdapterAttemptLoggingTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        LlmAttemptContext.clear();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void usesNonThinkingModeForOfficialDeepSeekStructuredRequests() throws Exception {
        HttpLlmAdapter adapter = new HttpLlmAdapter(
                new FakeModelConfigService("http://127.0.0.1"), new LlmAttemptLogger(new RecordingLogger()),
                5, 30, 1, 0, 0, true);
        Method method = HttpLlmAdapter.class.getDeclaredMethod("buildPayload",
                ModelConfigService.RuntimeModelConfig.class, String.class, String.class, Integer.class, boolean.class);
        method.setAccessible(true);
        String payload = (String) method.invoke(adapter,
                new ModelConfigService.RuntimeModelConfig(42L, "DEEPSEEK", "deepseek-v4-pro",
                        "https://api.deepseek.com/v1/chat/completions", "secret", "ACTIVE"),
                "return json", "{}", 1024, true);

        var body = new ObjectMapper().readTree(payload);
        assertEquals("disabled", body.path("thinking").path("type").asText());
        assertEquals("json_object", body.path("response_format").path("type").asText());
    }

    @Test
    void recordsRetryAndSuccessAttempts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        startServer(exchange -> {
            int current = calls.incrementAndGet();
            String body;
            int status;
            if (current == 1) {
                status = 500;
                body = "{\"error\":\"temporary provider failure\"}";
            } else {
                status = 200;
                body = "{\"choices\":[{\"message\":{\"content\":\"{\\\"result\\\":\\\"ok-json\\\"}\"}}]}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        RecordingLogger logger = new RecordingLogger();
        HttpLlmAdapter adapter = new HttpLlmAdapter(
                new FakeModelConfigService(serverBaseUrl()),
                new LlmAttemptLogger(logger),
                5,
                30,
                1,
                1,
                0,
                true
        );
        LlmAttemptContext.bind("req-attempt", request(), 100L, 200L);

        String result = adapter.complete(new LlmAdapter.CompletionRequest(42L, "请返回 JSON", "生成 JSON", 100));

        assertEquals("{\"result\":\"ok-json\"}", result);
        assertEquals(2, logger.entries.size());
        assertEquals("req-attempt#a1", logger.entries.get(0).requestId());
        assertEquals("ATTEMPT_RETRY", logger.entries.get(0).status());
        assertEquals("PROVIDER_ERROR", logger.entries.get(0).errorCode());
        assertEquals(1, logger.entries.get(0).retryIndex());
        assertEquals("req-attempt#a2", logger.entries.get(1).requestId());
        assertEquals("ATTEMPT_OK", logger.entries.get(1).status());
        assertEquals(2, logger.entries.get(1).retryIndex());
        assertEquals("{\"result\":\"ok-json\"}", logger.entries.get(1).rawOutputSnapshot());
    }

    @Test
    void parsesProviderUsageWhenPresent() throws Exception {
        startServer(exchange -> {
            String body = """
                    {
                      "choices": [{"message": {"content": "{\\"result\\":\\"ok-json\\"}"}}],
                      "usage": {"prompt_tokens": 123, "completion_tokens": 45, "total_tokens": 168,
                                "prompt_cache_hit_tokens": 80}
                    }
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        HttpLlmAdapter adapter = new HttpLlmAdapter(
                new FakeModelConfigService(serverBaseUrl()),
                null,
                5,
                30,
                1,
                0,
                0,
                true
        );

        LlmAdapter.CompletionResponse result = adapter.completeWithUsage(
                new LlmAdapter.CompletionRequest(42L, "请返回 JSON", "生成 JSON", 100));

        assertEquals("{\"result\":\"ok-json\"}", result.content());
        assertEquals(123, result.promptTokens());
        assertEquals(80, result.cachedPromptTokens());
        assertEquals(45, result.completionTokens());
    }

    @Test
    void keepsUsageEmptyWhenProviderDoesNotReturnUsage() throws Exception {
        startServer(exchange -> {
            String body = "{\"choices\":[{\"message\":{\"content\":\"plain\"}}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        HttpLlmAdapter adapter = new HttpLlmAdapter(
                new FakeModelConfigService(serverBaseUrl()),
                null,
                5,
                30,
                1,
                0,
                0,
                true
        );

        LlmAdapter.CompletionResponse result = adapter.completeWithUsage(
                new LlmAdapter.CompletionRequest(42L, "system", "user", 100));

        assertEquals("plain", result.content());
        org.junit.jupiter.api.Assertions.assertNull(result.promptTokens());
        org.junit.jupiter.api.Assertions.assertNull(result.completionTokens());
    }

    @Test
    void recordsNonRetryableAuthFailureAttemptOnce() throws Exception {
        startServer(exchange -> {
            String body = "{\"error\":\"invalid api key\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        RecordingLogger logger = new RecordingLogger();
        HttpLlmAdapter adapter = new HttpLlmAdapter(
                new FakeModelConfigService(serverBaseUrl()),
                new LlmAttemptLogger(logger),
                5,
                30,
                1,
                2,
                0,
                true
        );
        LlmAttemptContext.bind("req-auth", request(), 100L, 200L);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.company.aitest.llm.gateway.LlmRuntimeException.class,
                () -> adapter.complete(new LlmAdapter.CompletionRequest(42L, "system", "user", 100))
        );

        assertEquals(1, logger.entries.size());
        assertEquals("req-auth#a1", logger.entries.get(0).requestId());
        assertEquals("ATTEMPT_FAILED", logger.entries.get(0).status());
        assertEquals("AUTH_ERROR", logger.entries.get(0).errorCode());
        assertEquals(1, logger.entries.get(0).retryIndex());
    }


    @Test
    void retriesHttp524AsTimeout() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        startServer(exchange -> {
            int current = calls.incrementAndGet();
            String body;
            int status;
            if (current == 1) {
                status = 524;
                body = "error code: 524";
            } else {
                status = 200;
                body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        RecordingLogger logger = new RecordingLogger();
        HttpLlmAdapter adapter = new HttpLlmAdapter(
                new FakeModelConfigService(serverBaseUrl()),
                new LlmAttemptLogger(logger),
                5,
                30,
                1,
                1,
                0,
                true
        );
        LlmAttemptContext.bind("req-524", request(), 100L, 200L);

        String result = adapter.complete(new LlmAdapter.CompletionRequest(42L, "system", "user", 100));

        assertEquals("ok", result);
        assertEquals(2, logger.entries.size());
        assertEquals("ATTEMPT_RETRY", logger.entries.get(0).status());
        assertEquals("TIMEOUT", logger.entries.get(0).errorCode());
        assertEquals("ATTEMPT_OK", logger.entries.get(1).status());
    }

    @Test
    void retriesHttp200ResponseWhenProviderContentIsEmpty() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        startServer(exchange -> {
            String body = calls.incrementAndGet() == 1
                    ? "{\"choices\":[{\"message\":{\"content\":\"\"}}]}"
                    : "{\"choices\":[{\"message\":{\"content\":\"{\\\"result\\\":\\\"ok\\\"}\"}}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        RecordingLogger logger = new RecordingLogger();
        HttpLlmAdapter adapter = new HttpLlmAdapter(
                new FakeModelConfigService(serverBaseUrl()), new LlmAttemptLogger(logger),
                5, 30, 1, 1, 0, true);
        LlmAttemptContext.bind("req-empty", request(), 100L, 200L);

        assertEquals("{\"result\":\"ok\"}",
                adapter.complete(new LlmAdapter.CompletionRequest(42L, "请返回 JSON", "生成 JSON", 100)));
        assertEquals(2, calls.get());
        assertEquals("ATTEMPT_RETRY", logger.entries.get(0).status());
        assertEquals("PROVIDER_ERROR", logger.entries.get(0).errorCode());
        assertEquals("ATTEMPT_OK", logger.entries.get(1).status());
    }

    @Test
    void classifiesReasoningOnlyResponseWithoutRepeatingTheSameRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        startServer(exchange -> {
            calls.incrementAndGet();
            String body = """
                    {"choices":[{"finish_reason":"length","message":{"content":"","reasoning_content":"需要先仔细推理"}}]}
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        RecordingLogger logger = new RecordingLogger();
        HttpLlmAdapter adapter = new HttpLlmAdapter(
                new FakeModelConfigService(serverBaseUrl()), new LlmAttemptLogger(logger),
                5, 30, 1, 2, 0, true);
        LlmAttemptContext.bind("req-reasoning-only", request(), 100L, 200L);

        var error = assertThrows(com.company.aitest.llm.gateway.LlmRuntimeException.class,
                () -> adapter.complete(new LlmAdapter.CompletionRequest(42L, "请返回 JSON", "生成 JSON", 100)));

        assertEquals("REASONING_EXHAUSTED", error.errorCode().name());
        assertEquals(1, calls.get());
        assertEquals(1, logger.entries.size());
        assertEquals("ATTEMPT_FAILED", logger.entries.get(0).status());
        assertEquals("REASONING_EXHAUSTED", logger.entries.get(0).errorCode());
    }

    @Test
    void classifiesInsufficientBalanceAsNonRetryable() throws Exception {
        startServer(exchange -> {
            String body = "{\"error\":{\"message\":\"Insufficient Balance\",\"code\":\"invalid_request_error\"}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(402, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        RecordingLogger logger = new RecordingLogger();
        HttpLlmAdapter adapter = new HttpLlmAdapter(
                new FakeModelConfigService(serverBaseUrl()),
                new LlmAttemptLogger(logger),
                5,
                30,
                1,
                2,
                0,
                true
        );
        LlmAttemptContext.bind("req-balance", request(), 100L, 200L);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.company.aitest.llm.gateway.LlmRuntimeException.class,
                () -> adapter.complete(new LlmAdapter.CompletionRequest(42L, "system", "user", 100))
        );

        assertEquals(1, logger.entries.size());
        assertEquals("ATTEMPT_FAILED", logger.entries.get(0).status());
        assertEquals("INSUFFICIENT_BALANCE", logger.entries.get(0).errorCode());
        assertEquals(1, logger.entries.get(0).retryIndex());
    }


    @Test
    void missingModelConfigIsClassifiedWithoutCallingProvider() {
        HttpLlmAdapter adapter = new HttpLlmAdapter(
                new MissingModelConfigService(),
                null,
                5,
                30,
                1,
                0,
                0,
                true
        );

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.company.aitest.llm.gateway.LlmRuntimeException.class,
                () -> adapter.complete(new LlmAdapter.CompletionRequest(404L, "system", "user", 100))
        );

        assertEquals("MODEL_NOT_FOUND", ex.errorCode().name());
    }

    private void startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", handler);
        server.start();
    }

    private String serverBaseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private LlmInvocationRequest request() {
        return new LlmInvocationRequest(
                null,
                1L,
                2L,
                3L,
                "GENERATION",
                LlmStage.TEST_CASE_GEN,
                42L,
                null,
                null,
                Map.of(),
                "system",
                "user",
                null,
                100
        );
    }

    static class MissingModelConfigService extends ModelConfigService {
        MissingModelConfigService() {
            super(null, null, new TimeProvider(), null);
        }

        @Override
        public RuntimeModelConfig getRuntimeConfig(Long modelConfigId) {
            return null;
        }
    }

    static class FakeModelConfigService extends ModelConfigService {
        private final String endpoint;

        FakeModelConfigService(String endpoint) {
            super(null, null, new TimeProvider(), null);
            this.endpoint = endpoint;
        }

        @Override
        public RuntimeModelConfig getRuntimeConfig(Long modelConfigId) {
            return new RuntimeModelConfig(modelConfigId, "OTHER", "test-model", endpoint, "secret", "ACTIVE");
        }
    }

    static class RecordingLogger extends LlmInvocationLogger {
        final List<LlmInvocationLogEntry> entries = new ArrayList<>();

        RecordingLogger() {
            super(null, new TimeProvider());
        }

        @Override
        public Long record(LlmInvocationLogEntry entry) {
            entries.add(entry);
            return (long) entries.size();
        }
    }
}

package com.company.aitest.llm.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.llm.LlmAdapter;
import com.company.aitest.llm.gateway.audit.LlmInvocationLogEntry;
import com.company.aitest.llm.gateway.audit.LlmInvocationLogger;
import com.company.aitest.llm.gateway.context.ContextManifest;
import com.company.aitest.llm.gateway.context.ContextManifestRepository;
import com.company.aitest.llm.gateway.prompt.PromptSnapshotEntry;
import com.company.aitest.llm.gateway.prompt.PromptSnapshotService;
import com.company.aitest.llm.gateway.retrieval.RagRetrievalService;
import com.company.aitest.llm.gateway.retrieval.RetrievalPolicy;
import com.company.aitest.llm.gateway.retrieval.RetrievalResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 1 · M1.7 锁定回归点：
 *   - 任何路径都写一条 invocation log
 *   - 状态字段语义正确（OK / MODEL_ERROR / INVALID_REQUEST）
 *   - 两次连续 invoke 不共享内部状态（无跨 task / 跨 user 串味）
 *   - requestId 自动生成
 */
class LlmGatewayImplTest {

    @Test
    void writesLogOnSuccess() {
        FakeAdapter adapter = new FakeAdapter((req) -> "ok-response");
        RecordingLogger logger = new RecordingLogger();
        RecordingPromptSnapshot prompt = new RecordingPromptSnapshot();
        StubRetrieval retrieval = new StubRetrieval();
        RecordingManifest manifest = new RecordingManifest();

        LlmGatewayImpl gateway = new LlmGatewayImpl(adapter, prompt, logger, retrieval, manifest);

        LlmInvocationResponse resp = gateway.invoke(req(1L, 2L, 3L, LlmStage.TEST_CASE_GEN,
                "sys", "user"));

        assertEquals(LlmInvocationStatus.OK, resp.status());
        assertEquals("ok-response", resp.content());
        assertNotNull(resp.requestId());
        assertEquals(1, logger.entries.size());
        assertEquals("OK", logger.entries.get(0).status());
        assertEquals(1L, logger.entries.get(0).userId());
        assertEquals(1, manifest.entries.size(), "manifest must be persisted");
    }

    @Test
    void writesLogOnInvalidRequest() {
        FakeAdapter adapter = new FakeAdapter((r) -> "should-not-be-called");
        RecordingLogger logger = new RecordingLogger();
        RecordingPromptSnapshot prompt = new RecordingPromptSnapshot();
        StubRetrieval retrieval = new StubRetrieval();
        RecordingManifest manifest = new RecordingManifest();

        LlmGatewayImpl gateway = new LlmGatewayImpl(adapter, prompt, logger, retrieval, manifest);

        LlmInvocationResponse resp = gateway.invoke(new LlmInvocationRequest(
                null, null, 1L, 2L, "GENERATION", LlmStage.TEST_CASE_GEN,
                10L, null, null, Map.of(), "s", "u", null));

        assertEquals(LlmInvocationStatus.INVALID_REQUEST, resp.status());
        assertEquals(0, adapter.invocations.size(), "adapter must not be called when invalid");
        assertEquals(1, logger.entries.size());
        assertEquals("INVALID_REQUEST", logger.entries.get(0).status());
        assertEquals(0, manifest.entries.size(), "no manifest written when invalid");
    }

    @Test
    void writesLogOnModelError() {
        FakeAdapter adapter = new FakeAdapter((r) -> {
            throw new BusinessException("模型故障");
        });
        RecordingLogger logger = new RecordingLogger();
        LlmGatewayImpl gateway = new LlmGatewayImpl(adapter, new RecordingPromptSnapshot(), logger,
                new StubRetrieval(), new RecordingManifest());

        LlmInvocationResponse resp = gateway.invoke(req(1L, 2L, 3L, LlmStage.TEST_CASE_GEN, "s", "u"));

        assertEquals(LlmInvocationStatus.MODEL_ERROR, resp.status());
        assertEquals("模型故障", resp.errorMessage());
        assertEquals(1, logger.entries.size());
        assertEquals("MODEL_ERROR", logger.entries.get(0).status());
    }

    @Test
    void twoConsecutiveInvokesDoNotShareState() {
        // Gateway 实现不允许在自身保留任何 messages / context；两次调用的入参互不影响。
        List<String> seenUserPrompts = new ArrayList<>();
        FakeAdapter adapter = new FakeAdapter((r) -> {
            seenUserPrompts.add(r.userPrompt());
            return "resp";
        });
        LlmGatewayImpl gateway = new LlmGatewayImpl(adapter,
                new RecordingPromptSnapshot(), new RecordingLogger(),
                new StubRetrieval(), new RecordingManifest());

        gateway.invoke(req(1L, 100L, 1001L, LlmStage.TEST_CASE_GEN, "sys-A", "user-A"));
        gateway.invoke(req(2L, 200L, 1002L, LlmStage.TEST_CASE_GEN, "sys-B", "user-B"));

        assertEquals(2, seenUserPrompts.size());
        assertEquals("user-A", seenUserPrompts.get(0));
        assertEquals("user-B", seenUserPrompts.get(1));
        assertFalse(seenUserPrompts.get(1).contains("user-A"),
                "Second call must NOT contain any payload from the first call");
    }

    @Test
    void generatesRequestIdIfMissing() {
        FakeAdapter adapter = new FakeAdapter((r) -> "x");
        LlmGatewayImpl gateway = new LlmGatewayImpl(adapter,
                new RecordingPromptSnapshot(), new RecordingLogger(),
                new StubRetrieval(), new RecordingManifest());

        LlmInvocationResponse r1 = gateway.invoke(req(1L, 2L, 3L, LlmStage.TEST_CASE_GEN, "s", "u"));
        LlmInvocationResponse r2 = gateway.invoke(req(1L, 2L, 3L, LlmStage.TEST_CASE_GEN, "s", "u"));

        assertNotNull(r1.requestId());
        assertNotNull(r2.requestId());
        assertNotEquals(r1.requestId(), r2.requestId());
    }

    @Test
    void manifestExcludedPolicyAlwaysWrittenOnDisabledStage() {
        // SKILL_EXEC 的默认 policy 是 disabled，但 manifest 仍要写一条（excluded_policy="retrieval_disabled_by_policy"）。
        FakeAdapter adapter = new FakeAdapter((r) -> "x");
        RecordingManifest manifest = new RecordingManifest();
        LlmGatewayImpl gateway = new LlmGatewayImpl(adapter,
                new RecordingPromptSnapshot(), new RecordingLogger(),
                new StubRetrieval(), manifest);

        gateway.invoke(req(1L, 2L, 3L, LlmStage.SKILL_EXEC, "s", "u"));

        assertEquals(1, manifest.entries.size());
        assertTrue(manifest.entries.get(0).excludedPolicyJson() != null
                && manifest.entries.get(0).excludedPolicyJson().contains("retrieval_disabled_by_policy"));
    }

    // ---- helpers ----

    private static LlmInvocationRequest req(Long userId, Long projectId, Long taskId,
                                           LlmStage stage, String sys, String user) {
        return new LlmInvocationRequest(
                null, userId, projectId, taskId,
                "GENERATION", stage,
                42L, null, null, Map.of(),
                sys, user, null);
    }

    static class FakeAdapter implements LlmAdapter {
        private final java.util.function.Function<CompletionRequest, String> handler;
        final List<CompletionRequest> invocations = new ArrayList<>();

        FakeAdapter(java.util.function.Function<CompletionRequest, String> handler) {
            this.handler = handler;
        }

        @Override
        public String complete(CompletionRequest request) {
            invocations.add(request);
            return handler.apply(request);
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

    static class RecordingPromptSnapshot extends PromptSnapshotService {
        final List<PromptSnapshotEntry> entries = new ArrayList<>();

        RecordingPromptSnapshot() {
            super(null, new TimeProvider());
        }

        @Override
        public Long save(PromptSnapshotEntry entry) {
            entries.add(entry);
            return (long) entries.size();
        }
    }

    static class StubRetrieval extends RagRetrievalService {
        StubRetrieval() {
            super(null, null);
        }

        @Override
        public RetrievalResult retrieve(LlmInvocationRequest request, RetrievalPolicy policy) {
            return RetrievalResult.empty("{\"reason\":\"stub\"}");
        }
    }

    static class RecordingManifest extends ContextManifestRepository {
        final List<ContextManifest> entries = new ArrayList<>();

        RecordingManifest() {
            super(null, new TimeProvider());
        }

        @Override
        public Long save(ContextManifest manifest) {
            entries.add(manifest);
            return (long) entries.size();
        }
    }
}

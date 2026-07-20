package com.company.aitest.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.generation.session.ConversationOrchestrator;
import com.company.aitest.generation.session.GenerationSessionRecord;
import com.company.aitest.generation.session.GenerationSessionService;
import com.company.aitest.generation.session.RequirementAnalysisRecord;
import com.company.aitest.generation.session.RequirementAnalysisService;
import com.company.aitest.llm.gateway.LlmErrorCode;
import com.company.aitest.llm.gateway.LlmRuntimeException;
import com.company.aitest.model.ModelConfigService;
import com.company.aitest.prompt.PromptTemplateRecord;
import com.company.aitest.prompt.PromptTemplateService;
import com.company.aitest.trace.TraceSummaryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.ArgumentCaptor;

class AsyncGenerationTaskServiceTest {

    @Mock
    private GenerationTaskService taskService;
    @Mock
    private DirectCaseGenerationService caseGenerationService;
    @Mock
    private TestPointGenerationService testPointGenerationService;
    @Mock
    private TraceSummaryService traceSummaryService;
    @Mock
    private GenerationSessionService sessionService;
    @Mock
    private RequirementAnalysisService requirementAnalysisService;
    @Mock
    private ConversationOrchestrator conversationOrchestrator;
    @Mock
    private ModelConfigService modelConfigService;
    @Mock
    private PromptTemplateService promptTemplateService;

    private AutoCloseable mocks;
    private AsyncGenerationTaskService service;
    private final CurrentUser user = new CurrentUser(7L, "tester", "USER");
    private final LocalDateTime now = LocalDateTime.of(2026, 6, 30, 16, 45);

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        service = new AsyncGenerationTaskService(
                taskService,
                caseGenerationService,
                testPointGenerationService,
                traceSummaryService,
                sessionService,
                requirementAnalysisService,
                conversationOrchestrator,
                modelConfigService,
                promptTemplateService,
                1
        );
        when(modelConfigService.getRuntimeConfig(99L)).thenReturn(
                new ModelConfigService.RuntimeModelConfig(99L, "OTHER", "gpt-test", "https://model.example", "secret", "ACTIVE"));
        when(promptTemplateService.getById(55L)).thenReturn(promptTemplate());
    }

    @AfterEach
    void tearDown() throws Exception {
        service.shutdown();
        mocks.close();
    }

    @Test
    void startSessionCaseGenerationReusesActiveTaskForSameSessionAnalysis() {
        when(requirementAnalysisService.buildSessionGenerationPlan(20L, user)).thenReturn(plan());
        when(taskService.buildRequestHash(eq(10L), eq("TEST_CASE_GENERATION"), any(), eq(3), eq(99L),
                any(), eq("promptTemplateId=55|version=8|contentHash=hash-template"), eq("OTHER|gpt-test|https://model.example"), eq(7L)))
                .thenReturn("same-hash");
        GenerationTaskRecord existing = task(300L, "RUNNING");
        when(taskService.findActiveByHash(10L, "TEST_CASE_GENERATION", "same-hash"))
                .thenReturn(Optional.of(existing));
        when(taskService.draftCount(10L, 300L)).thenReturn(4);

        AsyncGenerationTaskService.TaskView view = service.startSessionCaseGeneration(10L, 20L, user);

        assertEquals(300L, view.taskId());
        assertEquals("RUNNING", view.status());
        assertEquals(4, view.draftCount());
        verify(sessionService).updateStatus(20L, "GENERATING");
        verify(sessionService).updateExecutionTaskId(20L, 300L);
        verify(taskService, never()).createAsync(any(), any(), any(), any(), any());
        verify(caseGenerationService, never()).generateFromTask(any(), any(), any());
    }

    @Test
    void startSessionCaseGenerationCompletesTaskAndLinksDraftsToSession() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        when(requirementAnalysisService.buildSessionGenerationPlan(20L, user)).thenReturn(plan());
        when(taskService.buildRequestHash(eq(10L), eq("TEST_CASE_GENERATION"), any(), eq(3), eq(99L),
                any(), eq("promptTemplateId=55|version=8|contentHash=hash-template"), eq("OTHER|gpt-test|https://model.example"), eq(7L)))
                .thenReturn("new-hash");
        when(taskService.findActiveByHash(10L, "TEST_CASE_GENERATION", "new-hash"))
                .thenReturn(Optional.empty());
        GenerationTaskRecord task = task(301L, "PENDING");
        when(taskService.createAsync(eq(10L), any(), eq("TEST_CASE_GENERATION"), eq("new-hash"), eq(user)))
                .thenReturn(task);
        when(taskService.get(10L, 301L)).thenReturn(task);
        when(taskService.markRunning(301L)).thenReturn(1);
        var result = new DirectCaseGenerationService.GenerateResult(301L, "raw", List.of(), 0, false, List.of(), List.of());
        when(caseGenerationService.generateFromTask(10L, 301L, user)).thenReturn(result);
        when(taskService.isCanceled(301L)).thenReturn(false);
        when(sessionService.findByExecutionTaskId(301L)).thenReturn(Optional.of(session()));
        org.mockito.Mockito.doAnswer(invocation -> {
            completed.countDown();
            return null;
        }).when(taskService).markSucceeded(301L);

        AsyncGenerationTaskService.TaskView view = service.startSessionCaseGeneration(10L, 20L, user);

        assertEquals(301L, view.taskId());
        assertEquals("PENDING", view.status());
        org.junit.jupiter.api.Assertions.assertTrue(completed.await(2, TimeUnit.SECONDS));
        verify(sessionService).updateStatus(20L, "GENERATING");
        verify(sessionService).updateExecutionTaskId(20L, 301L);
        verify(caseGenerationService).generateFromTask(10L, 301L, user);
        verify(requirementAnalysisService).completeAsyncGeneration(20L, 301L, user, result);
        verify(taskService).markSucceeded(301L);
    }

    @Test
    void startSessionCaseGenerationCarriesExplicitProjectTomModeIntoTask() throws Exception {
        var projectOnlyPlan = new RequirementAnalysisService.SessionGenerationPlan(
                10L, 20L, 30L, 3, "会话标题", "需求正文", "prompt snapshot",
                99L, 55L, true, "PROJECT_TOM");
        when(requirementAnalysisService.buildSessionGenerationPlan(20L, user)).thenReturn(projectOnlyPlan);
        when(taskService.buildRequestHash(eq(10L), eq("TEST_CASE_GENERATION"), any(), eq(3), eq(99L),
                any(), eq("promptTemplateId=55|version=8|contentHash=hash-template"),
                eq("OTHER|gpt-test|https://model.example"), eq(7L))).thenReturn("project-tom-hash");
        when(taskService.findActiveByHash(10L, "TEST_CASE_GENERATION", "project-tom-hash"))
                .thenReturn(Optional.empty());
        GenerationTaskRecord task = task(309L, "PENDING");
        when(taskService.createAsync(eq(10L), any(), eq("TEST_CASE_GENERATION"),
                eq("project-tom-hash"), eq(user))).thenReturn(task);
        when(taskService.get(10L, 309L)).thenReturn(task);
        when(taskService.markRunning(309L)).thenReturn(0);

        service.startSessionCaseGeneration(10L, 20L, user);

        ArgumentCaptor<GenerationTaskService.CreateTaskCommand> commandCaptor =
                ArgumentCaptor.forClass(GenerationTaskService.CreateTaskCommand.class);
        verify(taskService).createAsync(eq(10L), commandCaptor.capture(),
                eq("TEST_CASE_GENERATION"), eq("project-tom-hash"), eq(user));
        assertEquals("PROJECT_TOM", commandCaptor.getValue().generationMode());
        assertEquals(true, commandCaptor.getValue().useMiniTom());
    }

    @Test
    void startSessionCaseGenerationMarksFailureAndRestoresSessionWhenModelTimesOut() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        when(requirementAnalysisService.buildSessionGenerationPlan(20L, user)).thenReturn(plan());
        when(taskService.buildRequestHash(eq(10L), eq("TEST_CASE_GENERATION"), any(), eq(3), eq(99L),
                any(), eq("promptTemplateId=55|version=8|contentHash=hash-template"), eq("OTHER|gpt-test|https://model.example"), eq(7L)))
                .thenReturn("timeout-hash");
        when(taskService.findActiveByHash(10L, "TEST_CASE_GENERATION", "timeout-hash"))
                .thenReturn(Optional.empty());
        GenerationTaskRecord task = task(302L, "PENDING");
        when(taskService.createAsync(eq(10L), any(), eq("TEST_CASE_GENERATION"), eq("timeout-hash"), eq(user)))
                .thenReturn(task);
        when(taskService.get(10L, 302L)).thenReturn(task);
        when(taskService.markRunning(302L)).thenReturn(1);
        when(caseGenerationService.generateFromTask(10L, 302L, user))
                .thenThrow(new LlmRuntimeException(LlmErrorCode.TIMEOUT, "模型调用超时"));
        when(sessionService.findByExecutionTaskId(302L)).thenReturn(Optional.of(session()));
        org.mockito.Mockito.doAnswer(invocation -> {
            failed.countDown();
            return null;
        }).when(taskService).markFailed(eq(302L), eq("TIMEOUT"), eq("TIMEOUT"), eq("模型调用超时"));

        service.startSessionCaseGeneration(10L, 20L, user);

        org.junit.jupiter.api.Assertions.assertTrue(failed.await(2, TimeUnit.SECONDS));
        verify(taskService).markFailed(302L, "TIMEOUT", "TIMEOUT", "模型调用超时");
        verify(sessionService, timeout(2000)).updateStatus(20L, "ACTIVE");
        verify(requirementAnalysisService, never()).completeAsyncGeneration(any(), any(), any(), any());
    }

    @Test
    void startSessionCaseGenerationReusesTaskWhenTwoRequestsRace() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        when(requirementAnalysisService.buildSessionGenerationPlan(20L, user)).thenReturn(plan());
        when(taskService.buildRequestHash(eq(10L), eq("TEST_CASE_GENERATION"), any(), eq(3), eq(99L),
                any(), eq("promptTemplateId=55|version=8|contentHash=hash-template"), eq("OTHER|gpt-test|https://model.example"), eq(7L)))
                .thenReturn("race-hash");
        GenerationTaskRecord task = task(303L, "PENDING");
        AtomicInteger lookupCount = new AtomicInteger();
        when(taskService.findActiveByHash(10L, "TEST_CASE_GENERATION", "race-hash"))
                .thenAnswer(invocation -> lookupCount.getAndIncrement() == 0 ? Optional.empty() : Optional.of(task));
        when(taskService.createAsync(eq(10L), any(), eq("TEST_CASE_GENERATION"), eq("race-hash"), eq(user)))
                .thenReturn(task);
        when(taskService.get(10L, 303L)).thenReturn(task);
        when(taskService.markRunning(303L)).thenReturn(0);

        final AsyncGenerationTaskService.TaskView[] views = new AsyncGenerationTaskService.TaskView[2];
        Thread t1 = new Thread(() -> {
            await(start);
            views[0] = service.startSessionCaseGeneration(10L, 20L, user);
        });
        Thread t2 = new Thread(() -> {
            await(start);
            views[1] = service.startSessionCaseGeneration(10L, 20L, user);
        });
        t1.start();
        t2.start();
        start.countDown();
        t1.join(2000);
        t2.join(2000);

        assertEquals(303L, views[0].taskId());
        assertEquals(303L, views[1].taskId());
        verify(taskService, times(1)).createAsync(eq(10L), any(), eq("TEST_CASE_GENERATION"), eq("race-hash"), eq(user));
        verify(sessionService, times(2)).updateExecutionTaskId(20L, 303L);
    }

    @Test
    void startSessionIncrementalGenerationReusesActiveTask() {
        when(sessionService.get(10L, 20L, user)).thenReturn(session("ACTIVE", "CASE_READY", null));
        RequirementAnalysisRecord analysis = new RequirementAnalysisRecord(
                88L, 20L, 4, 0, "需求", "{}", "[]", "[]", "[]", "[]", "[]",
                "[]", "MINOR", "[]", "NEED_CONFIRMATION", now, now);
        when(requirementAnalysisService.getLatestAnalysis(20L)).thenReturn(analysis);
        when(taskService.buildRequestHash(eq(10L), eq("INCREMENTAL_CASE_GENERATION"), any(), eq(4), eq(99L),
                any(), eq(""), eq("OTHER|gpt-test|https://model.example"), eq(7L)))
                .thenReturn("incremental-hash");
        GenerationTaskRecord existing = task(307L, "RUNNING", "INCREMENTAL_CASE_GENERATION");
        when(taskService.findActiveByHash(10L, "INCREMENTAL_CASE_GENERATION", "incremental-hash"))
                .thenReturn(Optional.of(existing));

        AsyncGenerationTaskService.TaskView view = service.startSessionIncrementalGeneration(10L, 20L, List.of(9001), user);

        assertEquals(307L, view.taskId());
        assertEquals("INCREMENTAL_CASE_GENERATION", view.taskType());
        verify(sessionService).updateStatus(20L, "GENERATING");
        verify(sessionService).updateExecutionTaskId(20L, 307L);
        verify(taskService, never()).createAsync(any(), any(), any(), any(), any());
    }

    @Test
    void startSessionRequirementAnalysisAllowsSupplementStage() {
        GenerationSessionRecord session = session("ACTIVE", "WAITING_USER_CONFIRMATION", null);
        when(sessionService.get(10L, 20L, user)).thenReturn(session);
        when(taskService.buildRequestHash(eq(10L), eq("REQUIREMENT_ANALYSIS"), any(), eq(3), eq(99L),
                any(), eq(""), eq("OTHER|gpt-test|https://model.example"), eq(7L)))
                .thenReturn("supplement-hash");
        GenerationTaskRecord task = task(306L, "PENDING", "REQUIREMENT_ANALYSIS");
        when(taskService.findActiveByHash(10L, "REQUIREMENT_ANALYSIS", "supplement-hash"))
                .thenReturn(Optional.of(task));
        when(taskService.draftCount(10L, 306L)).thenReturn(0);

        AsyncGenerationTaskService.TaskView view = service.startSessionRequirementAnalysis(10L, 20L, "补充说明", user);

        assertEquals(306L, view.taskId());
        assertEquals("REQUIREMENT_ANALYSIS", view.taskType());
        assertEquals(1, view.stages().size());
        assertEquals("CORE", view.stages().get(0).code());
        assertEquals("理解需求与识别范围", view.stages().get(0).label());
        assertEquals("PENDING", view.stages().get(0).status());
        verify(sessionService).updateStatus(20L, "ANALYZING");
        verify(sessionService).updateExecutionTaskId(20L, 306L);
    }

    @Test
    void continuationTasksExposeOnlyTheirOwnPipelineStages() {
        GenerationTaskRecord requirementScope = task(320L, "PENDING", "REQUIREMENT_SCOPE_CONTINUATION");
        GenerationTaskRecord testPointScope = task(321L, "PENDING", "TEST_POINT_SCOPE_CONTINUATION");
        when(taskService.get(10L, 320L)).thenReturn(requirementScope);
        when(taskService.get(10L, 321L)).thenReturn(testPointScope);

        var requirementView = service.get(10L, 320L);
        var testPointView = service.get(10L, 321L);

        assertEquals(List.of("COVERAGE_MATRIX", "TEST_POINTS"),
                requirementView.stages().stream().map(AsyncGenerationTaskService.TaskStageView::code).toList());
        assertEquals(List.of("CASE_COMPOSITION"),
                testPointView.stages().stream().map(AsyncGenerationTaskService.TaskStageView::code).toList());
    }

    @Test
    void repeatedRequirementScopeConfirmationReturnsActiveContinuationWithoutReconfirming() {
        GenerationSessionRecord waiting = session("ANALYZING", "REQUIREMENT_ANALYZING", 322L);
        GenerationTaskRecord active = task(322L, "RUNNING", "REQUIREMENT_SCOPE_CONTINUATION");
        when(sessionService.get(10L, 20L, user)).thenReturn(waiting);
        when(taskService.get(10L, 322L)).thenReturn(active);
        when(taskService.draftCount(10L, 322L)).thenReturn(0);
        var decisions = List.of(new RequirementAnalysisService.RequirementScopeDecision(
                "R1", "GENERATE", "本期范围"));

        var view = service.confirmRequirementScopeAndContinue(10L, 20L, 3, decisions, user);

        assertEquals(322L, view.taskId());
        assertEquals("RUNNING", view.status());
        verify(requirementAnalysisService, never()).confirmRequirementScope(any(), any(Integer.class), any(), any());
    }

    @Test
    void repeatedTestPointScopeConfirmationReturnsActiveContinuationWithoutReconfirming() {
        GenerationSessionRecord waiting = session("ANALYZING", "REQUIREMENT_ANALYZING", 323L);
        GenerationTaskRecord active = task(323L, "RUNNING", "TEST_POINT_SCOPE_CONTINUATION");
        when(sessionService.get(10L, 20L, user)).thenReturn(waiting);
        when(taskService.get(10L, 323L)).thenReturn(active);
        when(taskService.draftCount(10L, 323L)).thenReturn(0);
        var decisions = List.of(new RequirementAnalysisService.TestPointScopeDecision(
                "TP1", "GENERATE", "本期范围"));

        var view = service.confirmTestPointScopeAndContinue(10L, 20L, 3, decisions, user);

        assertEquals(323L, view.taskId());
        assertEquals("RUNNING", view.status());
        verify(requirementAnalysisService, never()).confirmTestPointScope(any(), any(Integer.class), any(), any());
    }

    @Test
    void startSessionRequirementAnalysisRunsConversationThroughAsyncTask() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        GenerationSessionRecord askTomSession = session("ACTIVE", "ASK_TOM_MODE", null);
        when(sessionService.get(10L, 20L, user)).thenReturn(askTomSession);
        when(taskService.buildRequestHash(eq(10L), eq("REQUIREMENT_ANALYSIS"), any(), eq(3), eq(99L),
                any(), eq(""), eq("OTHER|gpt-test|https://model.example"), eq(7L)))
                .thenReturn("analysis-hash");
        when(taskService.findActiveByHash(10L, "REQUIREMENT_ANALYSIS", "analysis-hash"))
                .thenReturn(Optional.empty());
        GenerationTaskRecord task = task(304L, "PENDING", "REQUIREMENT_ANALYSIS");
        when(taskService.createAsync(eq(10L), any(), eq("REQUIREMENT_ANALYSIS"), eq("analysis-hash"), eq(user)))
                .thenReturn(task);
        when(taskService.get(10L, 304L)).thenReturn(task);
        when(taskService.markRunning(304L)).thenReturn(1);
        when(taskService.isCanceled(304L)).thenReturn(false);
        when(sessionService.findByExecutionTaskId(304L)).thenReturn(Optional.of(askTomSession));
        RequirementAnalysisRecord analysis = new RequirementAnalysisRecord(
                88L, 20L, 4, 0, "需求", "{}", "[]", "[]", "[]", "[]", "[]",
                "[]", "MINOR", "[]", "NEED_CONFIRMATION", now, now);
        when(conversationOrchestrator.processUserMessageForAsyncTask(20L, "使用 TOM", user))
                .thenReturn(new ConversationOrchestrator.ConversationReply(List.of(), analysis));
        org.mockito.Mockito.doAnswer(invocation -> {
            completed.countDown();
            return null;
        }).when(taskService).markSucceeded(304L);

        AsyncGenerationTaskService.TaskView view = service.startSessionRequirementAnalysis(10L, 20L, "使用 TOM", user);

        assertEquals(304L, view.taskId());
        assertEquals("REQUIREMENT_ANALYSIS", view.taskType());
        org.junit.jupiter.api.Assertions.assertTrue(completed.await(2, TimeUnit.SECONDS));
        verify(sessionService).updateStatus(20L, "ANALYZING");
        verify(sessionService).updateExecutionTaskId(20L, 304L);
        verify(conversationOrchestrator).processUserMessageForAsyncTask(20L, "使用 TOM", user);
        verify(taskService).markSucceeded(304L);
    }

    @Test
    void startSessionRequirementAnalysisPropagatesModelTimeoutToTask() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        GenerationSessionRecord askTomSession = session("ACTIVE", "ASK_TOM_MODE", null);
        when(sessionService.get(10L, 20L, user)).thenReturn(askTomSession);
        when(taskService.buildRequestHash(eq(10L), eq("REQUIREMENT_ANALYSIS"), any(), eq(3), eq(99L),
                any(), eq(""), eq("OTHER|gpt-test|https://model.example"), eq(7L)))
                .thenReturn("analysis-timeout-hash");
        when(taskService.findActiveByHash(10L, "REQUIREMENT_ANALYSIS", "analysis-timeout-hash"))
                .thenReturn(Optional.empty());
        GenerationTaskRecord task = task(305L, "PENDING", "REQUIREMENT_ANALYSIS");
        when(taskService.createAsync(eq(10L), any(), eq("REQUIREMENT_ANALYSIS"), eq("analysis-timeout-hash"), eq(user)))
                .thenReturn(task);
        when(taskService.get(10L, 305L)).thenReturn(task);
        when(taskService.markRunning(305L)).thenReturn(1);
        when(sessionService.findByExecutionTaskId(305L)).thenReturn(Optional.of(askTomSession));
        when(conversationOrchestrator.processUserMessageForAsyncTask(20L, "使用 TOM", user))
                .thenThrow(new LlmRuntimeException(LlmErrorCode.TIMEOUT, "模型调用超时"));
        org.mockito.Mockito.doAnswer(invocation -> {
            failed.countDown();
            return null;
        }).when(taskService).markFailed(eq(305L), eq("TIMEOUT"), eq("TIMEOUT"), eq("模型调用超时"));

        service.startSessionRequirementAnalysis(10L, 20L, "使用 TOM", user);

        org.junit.jupiter.api.Assertions.assertTrue(failed.await(2, TimeUnit.SECONDS));
        verify(taskService).markFailed(305L, "TIMEOUT", "TIMEOUT", "模型调用超时");
        verify(sessionService, timeout(2000)).updateStatus(20L, "ACTIVE");
    }

    @Test
    void retryingFailedAnalysisRestoresTomChoiceStageBeforeReplayingTask() throws Exception {
        GenerationSessionRecord failedSession = session("ACTIVE", "REQUIREMENT_INPUT", 306L);
        GenerationTaskRecord task = task(306L, "RUNNING", "REQUIREMENT_ANALYSIS");
        RequirementAnalysisRecord analysis = org.mockito.Mockito.mock(RequirementAnalysisRecord.class);
        when(sessionService.findByExecutionTaskId(306L)).thenReturn(Optional.of(failedSession));
        when(conversationOrchestrator.isExplicitTomModeChoice("使用 TOM")).thenReturn(true);
        when(conversationOrchestrator.processUserMessageForAsyncTask(20L, "使用 TOM", user))
                .thenReturn(new ConversationOrchestrator.ConversationReply(List.of(), analysis));

        Method method = AsyncGenerationTaskService.class.getDeclaredMethod(
                "runRequirementAnalysis", GenerationTaskRecord.class, CurrentUser.class);
        method.setAccessible(true);
        method.invoke(service, task, user);

        verify(sessionService).updateStage(20L, "ASK_TOM_MODE");
        verify(conversationOrchestrator).processUserMessageForAsyncTask(20L, "使用 TOM", user);
    }

    @Test
    void supplementalAnalysisKeepsPersistedTomModeInsteadOfReenteringTomChoice() throws Exception {
        GenerationSessionRecord waitingSession = session("ACTIVE", "WAITING_USER_CONFIRMATION", 310L);
        GenerationTaskRecord task = org.mockito.Mockito.mock(GenerationTaskRecord.class);
        when(task.id()).thenReturn(310L);
        when(task.requirementText()).thenReturn("补充：审批驳回时必须填写原因");
        RequirementAnalysisRecord analysis = org.mockito.Mockito.mock(RequirementAnalysisRecord.class);
        when(sessionService.findByExecutionTaskId(310L)).thenReturn(Optional.of(waitingSession));
        when(conversationOrchestrator.processUserMessageForAsyncTask(
                20L, "补充：审批驳回时必须填写原因", user))
                .thenReturn(new ConversationOrchestrator.ConversationReply(List.of(), analysis));

        Method method = AsyncGenerationTaskService.class.getDeclaredMethod(
                "runRequirementAnalysis", GenerationTaskRecord.class, CurrentUser.class);
        method.setAccessible(true);
        method.invoke(service, task, user);

        verify(sessionService, never()).updateStage(20L, "ASK_TOM_MODE");
        verify(conversationOrchestrator).processUserMessageForAsyncTask(
                20L, "补充：审批驳回时必须填写原因", user);
    }

    @Test
    void watchdogKeepsWorkingTaskWhenIdleAndMaximumLimitsAreNotReached() {
        when(taskService.markTimedOutIfIdle(eq(301L), any(LocalDateTime.class), any()))
                .thenReturn(false);
        when(taskService.markTimedOutIfExceededMaxRuntime(eq(301L), any(LocalDateTime.class), any()))
                .thenReturn(false);

        boolean timedOut = service.timeoutIfStalled(301L);

        assertEquals(false, timedOut);
        verify(taskService).markTimedOutIfIdle(eq(301L), any(LocalDateTime.class), any());
        verify(taskService).markTimedOutIfExceededMaxRuntime(eq(301L), any(LocalDateTime.class), any());
    }

    @Test
    void watchdogStopsOnlyWhenNoProgressTimeoutIsConfirmed() {
        when(taskService.markTimedOutIfIdle(eq(301L), any(LocalDateTime.class), any()))
                .thenReturn(true);

        boolean timedOut = service.timeoutIfStalled(301L);

        assertEquals(true, timedOut);
        verify(taskService).markTimedOutIfIdle(eq(301L), any(LocalDateTime.class), any());
        verify(taskService, never()).markTimedOutIfExceededMaxRuntime(any(), any(), any());
    }

    private RequirementAnalysisService.SessionGenerationPlan plan() {
        return new RequirementAnalysisService.SessionGenerationPlan(
                10L,
                20L,
                30L,
                3,
                "会话标题",
                "需求正文",
                "prompt snapshot",
                99L,
                55L,
                true
        );
    }

    private PromptTemplateRecord promptTemplate() {
        return new PromptTemplateRecord(
                55L,
                "模板",
                "CASE_GENERATION",
                "PUBLIC",
                "prompt body",
                "hash-template",
                "ACTIVE",
                8,
                "APPROVED",
                user.id(),
                user.username(),
                null,
                null,
                null,
                now,
                now
        );
    }

    private GenerationSessionRecord session() {
        return session("GENERATING", "CASE_READY", 301L);
    }

    private GenerationSessionRecord session(String status, String stage, Long executionTaskId) {
        return new GenerationSessionRecord(
                20L,
                10L,
                "会话标题",
                status,
                stage,
                99L,
                null,
                "prompt",
                true,
                3,
                executionTaskId,
                user.id(),
                now,
                now
        );
    }

    private GenerationTaskRecord task(Long id, String runStatus) {
        return task(id, runStatus, "TEST_CASE_GENERATION");
    }

    private GenerationTaskRecord task(Long id, String runStatus, String taskType) {
        return new GenerationTaskRecord(
                id,
                10L,
                "会话标题",
                "REQUIREMENT_ANALYSIS".equals(taskType) ? "使用 TOM" : "需求正文",
                null,
                "CREATED",
                "CREATED",
                taskType,
                runStatus,
                "hash",
                null,
                null,
                0,
                null,
                null,
                99L,
                55L,
                3,
                "prompt snapshot",
                user.id(),
                now,
                now,
                "MINI_TOM",
                true,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}

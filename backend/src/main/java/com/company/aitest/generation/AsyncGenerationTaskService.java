package com.company.aitest.generation;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.generation.session.ConversationOrchestrator;
import com.company.aitest.generation.session.GenerationSessionRecord;
import com.company.aitest.generation.session.GenerationSessionService;
import com.company.aitest.generation.session.RequirementAnalysisService;
import com.company.aitest.llm.gateway.LlmErrorCode;
import com.company.aitest.llm.gateway.LlmRuntimeException;
import com.company.aitest.model.ModelConfigService;
import com.company.aitest.prompt.PromptTemplateRecord;
import com.company.aitest.prompt.PromptTemplateService;
import com.company.aitest.trace.TraceSummaryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AsyncGenerationTaskService {
    private static final String TEST_CASE_GENERATION = "TEST_CASE_GENERATION";
    private static final String TEST_POINT_GENERATION = "TEST_POINT_GENERATION";
    private static final String TRACE_SUMMARY = "TRACE_SUMMARY";
    private static final String REQUIREMENT_ANALYSIS = "REQUIREMENT_ANALYSIS";
    private static final String REQUIREMENT_SCOPE_CONTINUATION = "REQUIREMENT_SCOPE_CONTINUATION";
    private static final String TEST_POINT_SCOPE_CONTINUATION = "TEST_POINT_SCOPE_CONTINUATION";
    private static final String INCREMENTAL_CASE_GENERATION = "INCREMENTAL_CASE_GENERATION";

    private final GenerationTaskService taskService;
    private final DirectCaseGenerationService caseGenerationService;
    private final TestPointGenerationService testPointGenerationService;
    private final TraceSummaryService traceSummaryService;
    private final GenerationSessionService sessionService;
    private final RequirementAnalysisService requirementAnalysisService;
    private final ConversationOrchestrator conversationOrchestrator;
    private final ModelConfigService modelConfigService;
    private final PromptTemplateService promptTemplateService;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor;
    private final ScheduledExecutorService timeoutScheduler;
    private final long taskIdleTimeoutSeconds;
    private final long taskMaxRuntimeSeconds;
    private final Object taskCreateLock = new Object();

    @Autowired
    public AsyncGenerationTaskService(GenerationTaskService taskService,
                                      DirectCaseGenerationService caseGenerationService,
                                      TestPointGenerationService testPointGenerationService,
                                      TraceSummaryService traceSummaryService,
                                      GenerationSessionService sessionService,
                                      RequirementAnalysisService requirementAnalysisService,
                                      ConversationOrchestrator conversationOrchestrator,
                                      ModelConfigService modelConfigService,
                                      PromptTemplateService promptTemplateService,
                                      JdbcClient jdbc,
                                      @Value("${aitest.generation.async-threads:2}") int asyncThreads,
                                      @Value("${aitest.generation.task-timeout-seconds:1800}") long taskIdleTimeoutSeconds,
                                      @Value("${aitest.generation.task-max-runtime-seconds:14400}") long taskMaxRuntimeSeconds) {
        this.taskService = taskService;
        this.caseGenerationService = caseGenerationService;
        this.testPointGenerationService = testPointGenerationService;
        this.traceSummaryService = traceSummaryService;
        this.sessionService = sessionService;
        this.requirementAnalysisService = requirementAnalysisService;
        this.conversationOrchestrator = conversationOrchestrator;
        this.modelConfigService = modelConfigService;
        this.promptTemplateService = promptTemplateService;
        this.jdbc = jdbc;
        this.executor = Executors.newFixedThreadPool(Math.max(1, asyncThreads));
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        this.taskIdleTimeoutSeconds = Math.max(60, taskIdleTimeoutSeconds);
        this.taskMaxRuntimeSeconds = Math.max(this.taskIdleTimeoutSeconds, taskMaxRuntimeSeconds);
    }

    AsyncGenerationTaskService(GenerationTaskService taskService,
                               DirectCaseGenerationService caseGenerationService,
                               TestPointGenerationService testPointGenerationService,
                               TraceSummaryService traceSummaryService,
                               GenerationSessionService sessionService,
                               RequirementAnalysisService requirementAnalysisService,
                               ConversationOrchestrator conversationOrchestrator,
                               ModelConfigService modelConfigService,
                               PromptTemplateService promptTemplateService,
                               int asyncThreads) {
        this(taskService, caseGenerationService, testPointGenerationService, traceSummaryService,
                sessionService, requirementAnalysisService, conversationOrchestrator, modelConfigService,
                promptTemplateService, null, asyncThreads, 1800, 14400);
    }

    public TaskView createOrReuse(Long projectId, CreateAsyncTaskCommand command, CurrentUser user) {
        String taskType = normalizeTaskType(command.taskType());
        String requestMaterial = requestMaterial(projectId, taskType, command, user);
        String promptContentHash = contentHash(command.promptSnapshot());
        String promptTemplateFingerprint = promptTemplateFingerprint(command.promptTemplateId());
        String modelFingerprint = modelFingerprint(command.modelConfigId());
        String requestHash = taskService.buildRequestHash(projectId, taskType, requestMaterial,
                command.promptVersion(), command.modelConfigId(), promptContentHash,
                promptTemplateFingerprint, modelFingerprint, user.id());

        GenerationTaskService.CreateTaskCommand createCommand = new GenerationTaskService.CreateTaskCommand(
                firstNonBlank(command.taskName(), defaultTaskName(taskType)),
                firstNonBlank(command.requirementText(), defaultRequirementText(taskType, command)),
                command.modelConfigId(),
                command.promptTemplateId(),
                command.promptVersion(),
                promptSnapshotFor(taskType, command),
                command.generationMode(),
                command.useMiniTom());
        CreateOrReuseResult result = findOrCreate(projectId, taskType, requestHash, createCommand, user);
        if (!result.created()) {
            return view(result.task(), taskService.draftCount(projectId, result.task().id()));
        }
        GenerationTaskRecord task = result.task();
        submit(task.projectId(), task.id(), user);
        return view(task, 0);
    }

    public TaskView startSessionCaseGeneration(Long projectId, Long sessionId, CurrentUser user) {
        RequirementAnalysisService.SessionGenerationPlan plan =
                requirementAnalysisService.buildSessionGenerationPlan(sessionId, user);
        if (!projectId.equals(plan.projectId())) {
            throw new BusinessException("会话不属于当前项目");
        }
        String taskType = TEST_CASE_GENERATION;
        CreateAsyncTaskCommand command = new CreateAsyncTaskCommand(
                taskType,
                plan.taskName(),
                plan.requirementText(),
                plan.modelConfigId(),
                plan.promptTemplateId(),
                plan.promptSnapshot(),
                plan.tomMode(),
                plan.useMiniTom(),
                plan.analysisVersion(),
                null,
                null,
                null,
                null,
                sessionId,
                plan.analysisId()
        );
        String requestMaterial = requestMaterial(projectId, taskType, command, user);
        String promptContentHash = contentHash(command.promptSnapshot());
        String promptTemplateFingerprint = promptTemplateFingerprint(command.promptTemplateId());
        String modelFingerprint = modelFingerprint(command.modelConfigId());
        String requestHash = taskService.buildRequestHash(projectId, taskType, requestMaterial,
                command.promptVersion(), command.modelConfigId(), promptContentHash,
                promptTemplateFingerprint, modelFingerprint, user.id());

        GenerationTaskService.CreateTaskCommand createCommand = new GenerationTaskService.CreateTaskCommand(
                firstNonBlank(command.taskName(), defaultTaskName(taskType)),
                command.requirementText(),
                command.modelConfigId(),
                command.promptTemplateId(),
                command.promptVersion(),
                command.promptSnapshot(),
                command.generationMode(),
                command.useMiniTom());
        CreateOrReuseResult result = findOrCreate(projectId, taskType, requestHash, createCommand, user);
        if (!result.created()) {
            sessionService.updateStatus(sessionId, "GENERATING");
            sessionService.updateExecutionTaskId(sessionId, result.task().id());
            return view(result.task(), taskService.draftCount(projectId, result.task().id()));
        }
        GenerationTaskRecord task = result.task();
        sessionService.updateStatus(sessionId, "GENERATING");
        sessionService.updateExecutionTaskId(sessionId, task.id());
        submit(projectId, task.id(), user);
        return view(task, 0);
    }

    public TaskView startSessionRequirementAnalysis(Long projectId, Long sessionId, String content, CurrentUser user) {
        GenerationSessionRecord session = sessionService.get(projectId, sessionId, user);
        if (session == null || !projectId.equals(session.projectId())) {
            throw new BusinessException("会话不属于当前项目");
        }
        String stage = session.currentStage() == null ? "" : session.currentStage().trim().toUpperCase();
        if (!isAsyncAnalysisStage(stage)) {
            throw new BusinessException("当前会话不支持启动异步需求分析");
        }
        String taskType = REQUIREMENT_ANALYSIS;
        String promptSnapshot = session.promptSnapshot();
        CreateAsyncTaskCommand command = new CreateAsyncTaskCommand(
                taskType,
                "异步需求分析",
                firstNonBlank(content, "使用 TOM"),
                session.modelConfigId(),
                session.promptTemplateId(),
                promptSnapshot,
                session.tomMode(),
                session.useMiniTom(),
                session.latestAnalysisVersion(),
                null,
                null,
                null,
                null,
                sessionId,
                null
        );
        String requestMaterial = requestMaterial(projectId, taskType, command, user);
        String promptContentHash = contentHash(command.promptSnapshot());
        String promptTemplateFingerprint = promptTemplateFingerprint(command.promptTemplateId());
        String modelFingerprint = modelFingerprint(command.modelConfigId());
        String requestHash = taskService.buildRequestHash(projectId, taskType, requestMaterial,
                command.promptVersion(), command.modelConfigId(), promptContentHash,
                promptTemplateFingerprint, modelFingerprint, user.id());

        GenerationTaskService.CreateTaskCommand createCommand = new GenerationTaskService.CreateTaskCommand(
                firstNonBlank(command.taskName(), defaultTaskName(taskType)),
                command.requirementText(),
                command.modelConfigId(),
                command.promptTemplateId(),
                command.promptVersion(),
                command.promptSnapshot(),
                command.generationMode(),
                command.useMiniTom());
        CreateOrReuseResult result = findOrCreate(projectId, taskType, requestHash, createCommand, user);
        sessionService.updateStatus(sessionId, "ANALYZING");
        sessionService.updateExecutionTaskId(sessionId, result.task().id());
        if (result.created()) {
            submit(projectId, result.task().id(), user);
        }
        return view(result.task(), taskService.draftCount(projectId, result.task().id()));
    }

    public TaskView confirmRequirementScopeAndContinue(Long projectId, Long sessionId, int analysisVersion,
                                                       java.util.List<RequirementAnalysisService.RequirementScopeDecision> decisions,
                                                       CurrentUser user) {
        synchronized (taskCreateLock) {
            GenerationSessionRecord session = sessionService.get(projectId, sessionId, user);
            if (session == null || !projectId.equals(session.projectId())) {
                throw new BusinessException("会话不属于当前项目");
            }
            TaskView active = activeContinuationTask(projectId, session, REQUIREMENT_SCOPE_CONTINUATION);
            if (active != null) return active;
            var analysis = requirementAnalysisService.confirmRequirementScope(sessionId, analysisVersion, decisions, user);
            String taskType = REQUIREMENT_SCOPE_CONTINUATION;
            String payload = requirementScopePayload(sessionId, analysisVersion, analysis.id());
            CreateAsyncTaskCommand command = new CreateAsyncTaskCommand(
                    taskType, "按已确认范围生成测试点", "继续分析范围版本 " + analysisVersion,
                    session.modelConfigId(), session.promptTemplateId(), payload, session.tomMode(),
                    session.useMiniTom(), analysisVersion, null, null, null, null, sessionId, null);
            String requestMaterial = requestMaterial(projectId, taskType, command, user)
                    + "|analysis=" + contentHash(analysis.analysisResult());
            String requestHash = taskService.buildRequestHash(projectId, taskType, requestMaterial,
                    analysisVersion, session.modelConfigId(), contentHash(payload),
                    promptTemplateFingerprint(session.promptTemplateId()), modelFingerprint(session.modelConfigId()), user.id());
            GenerationTaskService.CreateTaskCommand createCommand = new GenerationTaskService.CreateTaskCommand(
                    command.taskName(), command.requirementText(), command.modelConfigId(), command.promptTemplateId(),
                    command.promptVersion(), payload, command.generationMode(), command.useMiniTom());
            CreateOrReuseResult result = findOrCreate(projectId, taskType, requestHash, createCommand, user);
            sessionService.updateStatus(sessionId, "ANALYZING");
            sessionService.updateStage(sessionId, "REQUIREMENT_ANALYZING");
            sessionService.updateExecutionTaskId(sessionId, result.task().id());
            if (result.created()) submit(projectId, result.task().id(), user);
            return view(result.task(), 0);
        }
    }

    public TaskView confirmTestPointScopeAndContinue(Long projectId, Long sessionId, int analysisVersion,
                                                     java.util.List<RequirementAnalysisService.TestPointScopeDecision> decisions,
                                                     CurrentUser user) {
        synchronized (taskCreateLock) {
            GenerationSessionRecord session = sessionService.get(projectId, sessionId, user);
            if (session == null || !projectId.equals(session.projectId())) {
                throw new BusinessException("会话不属于当前项目");
            }
            TaskView active = activeContinuationTask(projectId, session, TEST_POINT_SCOPE_CONTINUATION);
            if (active != null) return active;
            var analysis = requirementAnalysisService.confirmTestPointScope(sessionId, analysisVersion, decisions, user);
            String taskType = TEST_POINT_SCOPE_CONTINUATION;
            String payload = requirementScopePayload(sessionId, analysisVersion, analysis.id());
            CreateAsyncTaskCommand command = new CreateAsyncTaskCommand(
                    taskType, "按已确认测试点生成编排", "继续测试点范围版本 " + analysisVersion,
                    session.modelConfigId(), session.promptTemplateId(), payload, session.tomMode(),
                    session.useMiniTom(), analysisVersion, null, null, null, null, sessionId, null);
            String requestMaterial = requestMaterial(projectId, taskType, command, user)
                    + "|testPoints=" + contentHash(analysis.testPoints());
            String requestHash = taskService.buildRequestHash(projectId, taskType, requestMaterial,
                    analysisVersion, session.modelConfigId(), contentHash(payload),
                    promptTemplateFingerprint(session.promptTemplateId()), modelFingerprint(session.modelConfigId()), user.id());
            GenerationTaskService.CreateTaskCommand createCommand = new GenerationTaskService.CreateTaskCommand(
                    command.taskName(), command.requirementText(), command.modelConfigId(), command.promptTemplateId(),
                    command.promptVersion(), payload, command.generationMode(), command.useMiniTom());
            CreateOrReuseResult result = findOrCreate(projectId, taskType, requestHash, createCommand, user);
            sessionService.updateStatus(sessionId, "ANALYZING");
            sessionService.updateStage(sessionId, "REQUIREMENT_ANALYZING");
            sessionService.updateExecutionTaskId(sessionId, result.task().id());
            if (result.created()) submit(projectId, result.task().id(), user);
            return view(result.task(), 0);
        }
    }

    private TaskView activeContinuationTask(Long projectId, GenerationSessionRecord session, String expectedType) {
        if (session.executionTaskId() == null) return null;
        try {
            GenerationTaskRecord task = taskService.get(projectId, session.executionTaskId());
            String status = task.runStatus() == null ? "" : task.runStatus().trim().toUpperCase();
            if (expectedType.equals(normalizeTaskType(task.taskType()))
                    && java.util.Set.of("PENDING", "RUNNING").contains(status)) {
                return view(task, taskService.draftCount(projectId, task.id()));
            }
        } catch (BusinessException ignored) {
            // A stale execution task must not block a new confirmed continuation.
        }
        return null;
    }

    public TaskView startSessionIncrementalGeneration(Long projectId, Long sessionId, java.util.List<Integer> selectedDraftIds,
                                                      CurrentUser user) {
        GenerationSessionRecord session = sessionService.get(projectId, sessionId, user);
        if (session == null || !projectId.equals(session.projectId())) {
            throw new BusinessException("会话不属于当前项目");
        }
        if (selectedDraftIds == null || selectedDraftIds.isEmpty()) {
            throw new BusinessException("请至少选择一个有效的用例");
        }
        var latest = requirementAnalysisService.getLatestAnalysis(sessionId);
        if (latest == null) {
            throw new BusinessException("没有分析结果");
        }
        String taskType = INCREMENTAL_CASE_GENERATION;
        String payload = incrementalPayload(selectedDraftIds);
        CreateAsyncTaskCommand command = new CreateAsyncTaskCommand(
                taskType,
                "异步增量用例更新",
                "增量更新用例:" + selectedDraftIds,
                session.modelConfigId(),
                session.promptTemplateId(),
                payload,
                session.tomMode(),
                session.useMiniTom(),
                latest.version(),
                null,
                null,
                null,
                null,
                sessionId,
                latest.id()
        );
        String requestMaterial = requestMaterial(projectId, taskType, command, user);
        String promptContentHash = contentHash(command.promptSnapshot());
        String promptTemplateFingerprint = promptTemplateFingerprint(command.promptTemplateId());
        String modelFingerprint = modelFingerprint(command.modelConfigId());
        String requestHash = taskService.buildRequestHash(projectId, taskType, requestMaterial,
                command.promptVersion(), command.modelConfigId(), promptContentHash,
                promptTemplateFingerprint, modelFingerprint, user.id());

        GenerationTaskService.CreateTaskCommand createCommand = new GenerationTaskService.CreateTaskCommand(
                firstNonBlank(command.taskName(), defaultTaskName(taskType)),
                command.requirementText(),
                command.modelConfigId(),
                command.promptTemplateId(),
                command.promptVersion(),
                command.promptSnapshot(),
                command.generationMode(),
                command.useMiniTom());
        CreateOrReuseResult result = findOrCreate(projectId, taskType, requestHash, createCommand, user);
        sessionService.updateStatus(sessionId, "GENERATING");
        sessionService.updateExecutionTaskId(sessionId, result.task().id());
        if (result.created()) {
            submit(projectId, result.task().id(), user);
        }
        return view(result.task(), taskService.draftCount(projectId, result.task().id()));
    }

    private boolean isAsyncAnalysisStage(String stage) {
        return "ASK_TOM_MODE".equals(stage)
                || "WAITING_REQUIREMENT_SCOPE".equals(stage)
                || "WAITING_USER_CONFIRMATION".equals(stage)
                || "ANALYSIS_READY".equals(stage);
    }

    private CreateOrReuseResult findOrCreate(Long projectId, String taskType, String requestHash,
                                             GenerationTaskService.CreateTaskCommand createCommand,
                                             CurrentUser user) {
        synchronized (taskCreateLock) {
            var existing = taskService.findActiveByHash(projectId, taskType, requestHash);
            if (existing.isPresent()) {
                return new CreateOrReuseResult(existing.get(), false);
            }
            return new CreateOrReuseResult(
                    taskService.createAsync(projectId, createCommand, taskType, requestHash, user),
                    true);
        }
    }

    public TaskView get(Long projectId, Long taskId) {
        GenerationTaskRecord task = taskService.get(projectId, taskId);
        int draftCount = taskService.draftCount(projectId, taskId);
        if (TEST_CASE_GENERATION.equals(normalizeTaskType(task.taskType()))
                && java.util.Set.of("PENDING", "RUNNING").contains(normalizedStatus(task.runStatus()))
                && draftCount > 0
                && sessionService.findByExecutionTaskId(taskId)
                    .map(GenerationSessionRecord::status)
                    .map(this::normalizedStatus)
                    .filter("COMPLETED"::equals)
                    .isPresent()
                && taskService.markSucceededIfOutputCommitted(taskId)) {
            task = taskService.get(projectId, taskId);
        }
        return view(task, draftCount);
    }

    private String normalizedStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase();
    }

    public TaskView retry(Long projectId, Long taskId, CurrentUser user) {
        if (!taskService.resetForRetry(projectId, taskId)) {
            throw new BusinessException("只有失败或超时的任务可以重试");
        }
        submit(projectId, taskId, user);
        return get(projectId, taskId);
    }

    public TaskView cancel(Long projectId, Long taskId) {
        if (!taskService.cancel(projectId, taskId)) {
            throw new BusinessException("只有待执行或执行中的任务可以取消");
        }
        return get(projectId, taskId);
    }

    private void submit(Long projectId, Long taskId, CurrentUser user) {
        AtomicReference<Future<?>> executionRef = new AtomicReference<>();
        long watchdogIntervalSeconds = Math.min(60, Math.max(5, taskIdleTimeoutSeconds / 6));
        ScheduledFuture<?> watchdog = timeoutScheduler.scheduleWithFixedDelay(() -> {
            Future<?> execution = executionRef.get();
            if (execution == null || execution.isDone()) {
                return;
            }
            if (timeoutIfStalled(taskId)) {
                execution.cancel(true);
            }
        }, watchdogIntervalSeconds, watchdogIntervalSeconds, TimeUnit.SECONDS);
        Future<?> execution = executor.submit(() -> {
            try {
                run(projectId, taskId, user);
            } finally {
                watchdog.cancel(false);
            }
        });
        executionRef.set(execution);
    }

    /** Package-visible for a focused watchdog test. */
    boolean timeoutIfStalled(Long taskId) {
        LocalDateTime now = LocalDateTime.now();
        if (taskService.markTimedOutIfIdle(taskId, now.minusSeconds(taskIdleTimeoutSeconds),
                "异步任务在 " + taskIdleTimeoutSeconds + " 秒内没有生成新的节点、检查点或草稿；已停止等待，可从失败节点继续。")) {
            return true;
        }
        return taskService.markTimedOutIfExceededMaxRuntime(taskId, now.minusSeconds(taskMaxRuntimeSeconds),
                "异步任务超过最大运行时长 " + taskMaxRuntimeSeconds + " 秒；已停止等待，可从失败节点继续。");
    }

    private void run(Long projectId, Long taskId, CurrentUser user) {
        GenerationTaskRecord task = taskService.get(projectId, taskId);
        if (taskService.markRunning(taskId) == 0) {
            return;
        }
        taskService.touchProgress(taskId);
        try {
            switch (normalizeTaskType(task.taskType())) {
                case TEST_CASE_GENERATION -> {
                    DirectCaseGenerationService.GenerateResult result =
                            caseGenerationService.generateFromTask(projectId, taskId, user);
                    if (!taskService.isCanceled(taskId)) {
                        linkSessionDraftsIfNeeded(taskId, user, result);
                    }
                }
                case TEST_POINT_GENERATION -> testPointGenerationService.generateTestPoints(projectId, taskId, user);
                case TRACE_SUMMARY -> runTraceSummary(task, user);
                case REQUIREMENT_ANALYSIS -> runRequirementAnalysis(task, user);
                case REQUIREMENT_SCOPE_CONTINUATION -> runRequirementScopeContinuation(task, user);
                case TEST_POINT_SCOPE_CONTINUATION -> runTestPointScopeContinuation(task, user);
                case INCREMENTAL_CASE_GENERATION -> runIncrementalCaseGeneration(task, user);
                default -> throw new BusinessException("不支持的任务类型：" + task.taskType());
            }
            if (!taskService.isCanceled(taskId)) {
                taskService.markSucceeded(taskId);
            }
        } catch (LlmRuntimeException ex) {
            taskService.markFailed(taskId, runStatusFor(ex.errorCode()), ex.errorCode().name(), ex.getMessage());
            markLinkedSessionActive(taskId, task.taskType());
        } catch (BusinessException ex) {
            taskService.markFailed(taskId, "FAILED", LlmErrorCode.UNKNOWN_ERROR.name(), ex.getMessage());
            markLinkedSessionActive(taskId, task.taskType());
        } catch (RuntimeException ex) {
            taskService.markFailed(taskId, "FAILED", LlmErrorCode.UNKNOWN_ERROR.name(), ex.getMessage());
            markLinkedSessionActive(taskId, task.taskType());
        }
    }

    private void linkSessionDraftsIfNeeded(Long taskId, CurrentUser user,
                                           DirectCaseGenerationService.GenerateResult result) {
        sessionService.findByExecutionTaskId(taskId)
                .ifPresent(session -> requirementAnalysisService.completeAsyncGeneration(session.id(), taskId, user, result));
    }

    private void markLinkedSessionActive(Long taskId, String taskType) {
        sessionService.findByExecutionTaskId(taskId)
                .ifPresent(session -> {
                    sessionService.updateStatus(session.id(), "ACTIVE");
                    String normalized = normalizeTaskType(taskType);
                    if (REQUIREMENT_SCOPE_CONTINUATION.equals(normalized)) {
                        sessionService.updateStage(session.id(), "WAITING_REQUIREMENT_SCOPE");
                    } else if (TEST_POINT_SCOPE_CONTINUATION.equals(normalized)) {
                        sessionService.updateStage(session.id(), "WAITING_USER_CONFIRMATION");
                    }
                });
    }

    private void runRequirementAnalysis(GenerationTaskRecord task, CurrentUser user) {
        GenerationSessionRecord session = sessionService.findByExecutionTaskId(task.id())
                .orElseThrow(() -> new BusinessException("需求分析任务未关联会话"));
        // Only the initial TOM-choice task is allowed to enter ASK_TOM_MODE. Supplemental and
        // re-analysis tasks must keep the session's persisted mode; otherwise arbitrary user
        // text is interpreted as PROJECT_AND_SYSTEM_TOM by the TOM-choice fallback.
        String stage = session.currentStage() == null ? "" : session.currentStage().trim().toUpperCase();
        if ("REQUIREMENT_INPUT".equals(stage)
                && conversationOrchestrator.isExplicitTomModeChoice(task.requirementText())) {
            sessionService.updateStage(session.id(), "ASK_TOM_MODE");
        }
        var reply = conversationOrchestrator.processUserMessageForAsyncTask(session.id(), task.requirementText(), user);
        if (reply == null || reply.analysis() == null) {
            throw new BusinessException("需求分析失败，详见当前会话消息");
        }
    }

    private void runRequirementScopeContinuation(GenerationTaskRecord task, CurrentUser user) {
        RequirementScopePayload payload = readRequirementScopePayload(task.promptSnapshot());
        GenerationSessionRecord session = sessionService.findByExecutionTaskId(task.id())
                .orElseThrow(() -> new BusinessException("需求范围继续任务未关联会话"));
        if (!session.id().equals(payload.sessionId())) {
            throw new BusinessException("需求范围继续任务与会话不匹配");
        }
        requirementAnalysisService.continueAfterRequirementScope(session.id(), payload.analysisVersion(), user);
    }

    private void runTestPointScopeContinuation(GenerationTaskRecord task, CurrentUser user) {
        RequirementScopePayload payload = readRequirementScopePayload(task.promptSnapshot());
        GenerationSessionRecord session = sessionService.findByExecutionTaskId(task.id())
                .orElseThrow(() -> new BusinessException("测试点范围继续任务未关联会话"));
        if (!session.id().equals(payload.sessionId())) {
            throw new BusinessException("测试点范围继续任务与会话不匹配");
        }
        requirementAnalysisService.continueAfterTestPointScope(session.id(), payload.analysisVersion(), user);
    }

    private void runIncrementalCaseGeneration(GenerationTaskRecord task, CurrentUser user) {
        GenerationSessionRecord session = sessionService.findByExecutionTaskId(task.id())
                .orElseThrow(() -> new BusinessException("增量生成任务未关联会话"));
        IncrementalCasePayload payload = readIncrementalPayload(task.promptSnapshot());
        var result = requirementAnalysisService.incrementalGenerate(session.id(), payload.selectedDraftIds(), user);
        if (result.updatedCount() <= 0) {
            throw new BusinessException("增量生成未更新任何用例");
        }
        sessionService.updateStatus(session.id(), "ACTIVE");
    }

    private void runTraceSummary(GenerationTaskRecord task, CurrentUser user) {
        TraceTaskPayload payload = readTracePayload(task.promptSnapshot());
        if (payload.traceGroupId() == null) {
            throw new LlmRuntimeException(LlmErrorCode.INVALID_REQUEST, "TRACE_SUMMARY 缺少 traceGroupId");
        }
        traceSummaryService.generateSummary(payload.traceGroupId(),
                new TraceSummaryService.GenerateSummaryCommand(
                        task.modelConfigId(), payload.traceSessionId(), payload.issueClipId(), payload.summaryScope()),
                user);
    }

    private String runStatusFor(LlmErrorCode code) {
        return code == LlmErrorCode.TIMEOUT ? "TIMEOUT" : "FAILED";
    }

    private String normalizeTaskType(String taskType) {
        String value = taskType == null ? "" : taskType.trim().toUpperCase();
        return switch (value) {
            case TEST_CASE_GENERATION, "GENERATION", "TEST_CASE_GEN" -> TEST_CASE_GENERATION;
            case TEST_POINT_GENERATION, "TEST_POINT_GEN" -> TEST_POINT_GENERATION;
            case TRACE_SUMMARY -> TRACE_SUMMARY;
            case REQUIREMENT_ANALYSIS, "ANALYSIS", "REQUIREMENT_ANALYSIS_ASYNC" -> REQUIREMENT_ANALYSIS;
            case REQUIREMENT_SCOPE_CONTINUATION, "SCOPE_CONTINUATION" -> REQUIREMENT_SCOPE_CONTINUATION;
            case TEST_POINT_SCOPE_CONTINUATION, "TEST_POINT_SCOPE" -> TEST_POINT_SCOPE_CONTINUATION;
            case INCREMENTAL_CASE_GENERATION, "INCREMENTAL", "INCREMENTAL_CASE_GEN" -> INCREMENTAL_CASE_GENERATION;
            default -> throw new BusinessException("不支持的任务类型：" + taskType);
        };
    }

    private String requestMaterial(Long projectId, String taskType, CreateAsyncTaskCommand command, CurrentUser user) {
        try {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("projectId", projectId);
            material.put("taskType", taskType);
            material.put("requirementText", empty(command.requirementText()));
            material.put("promptSnapshot", empty(command.promptSnapshot()));
            material.put("promptTemplateId", command.promptTemplateId());
            material.put("promptTemplateFingerprint", promptTemplateFingerprint(command.promptTemplateId()));
            material.put("modelConfigId", command.modelConfigId());
            material.put("tomMode", empty(command.generationMode()));
            material.put("useMiniTom", Boolean.TRUE.equals(command.useMiniTom()));
            material.put("traceGroupId", command.traceGroupId());
            material.put("traceSessionId", command.traceSessionId());
            material.put("issueClipId", command.issueClipId());
            material.put("summaryScope", empty(command.summaryScope()));
            material.put("sessionId", command.sessionId());
            material.put("analysisId", command.analysisId());
            material.put("createdBy", user.id());
            return objectMapper.writeValueAsString(material);
        } catch (JsonProcessingException e) {
            throw new BusinessException("生成请求指纹失败");
        }
    }

    private String incrementalPayload(java.util.List<Integer> selectedDraftIds) {
        try {
            return objectMapper.writeValueAsString(new IncrementalCasePayload(selectedDraftIds));
        } catch (JsonProcessingException e) {
            throw new BusinessException("增量生成任务参数序列化失败");
        }
    }

    private IncrementalCasePayload readIncrementalPayload(String payload) {
        try {
            IncrementalCasePayload parsed = objectMapper.readValue(payload == null || payload.isBlank() ? "{}" : payload,
                    IncrementalCasePayload.class);
            if (parsed.selectedDraftIds() == null || parsed.selectedDraftIds().isEmpty()) {
                throw new BusinessException("INCREMENTAL_CASE_GENERATION 缺少 selectedDraftIds");
            }
            return parsed;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmRuntimeException(LlmErrorCode.INVALID_REQUEST, "INCREMENTAL_CASE_GENERATION 参数解析失败");
        }
    }

    private String requirementScopePayload(Long sessionId, int analysisVersion, Long analysisId) {
        try {
            return objectMapper.writeValueAsString(new RequirementScopePayload(sessionId, analysisVersion, analysisId));
        } catch (JsonProcessingException e) {
            throw new BusinessException("需求范围继续任务参数序列化失败");
        }
    }

    private RequirementScopePayload readRequirementScopePayload(String payload) {
        try {
            RequirementScopePayload parsed = objectMapper.readValue(
                    payload == null || payload.isBlank() ? "{}" : payload, RequirementScopePayload.class);
            if (parsed.sessionId() == null || parsed.analysisVersion() == null) {
                throw new BusinessException("需求范围继续任务缺少会话或分析版本");
            }
            return parsed;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmRuntimeException(LlmErrorCode.INVALID_REQUEST, "需求范围继续任务参数解析失败");
        }
    }

    private String promptSnapshotFor(String taskType, CreateAsyncTaskCommand command) {
        if (!TRACE_SUMMARY.equals(taskType)) {
            return command.promptSnapshot();
        }
        try {
            return objectMapper.writeValueAsString(new TraceTaskPayload(
                    command.traceGroupId(), command.traceSessionId(), command.issueClipId(), command.summaryScope()));
        } catch (JsonProcessingException e) {
            throw new BusinessException("轨迹摘要任务参数序列化失败");
        }
    }

    private TraceTaskPayload readTracePayload(String payload) {
        try {
            return objectMapper.readValue(payload == null || payload.isBlank() ? "{}" : payload, TraceTaskPayload.class);
        } catch (Exception e) {
            throw new LlmRuntimeException(LlmErrorCode.INVALID_REQUEST, "TRACE_SUMMARY 参数解析失败");
        }
    }

    private TaskView view(GenerationTaskRecord task, int draftCount) {
        return new TaskView(task.id(), task.taskType(), task.runStatus(), task.errorCode(), task.errorMessage(),
                draftCount, task.createdAt(), task.updatedAt(), stageViews(task));
    }

    private java.util.List<TaskStageView> stageViews(GenerationTaskRecord task) {
        String normalizedType;
        try {
            normalizedType = normalizeTaskType(task.taskType());
        } catch (BusinessException ignored) {
            return java.util.List.of();
        }
        if (!REQUIREMENT_ANALYSIS.equals(normalizedType)
                && !REQUIREMENT_SCOPE_CONTINUATION.equals(normalizedType)
                && !TEST_POINT_SCOPE_CONTINUATION.equals(normalizedType)
                && !TEST_CASE_GENERATION.equals(normalizedType)) {
            return java.util.List.of();
        }
        java.util.List<InvocationStageLog> logs = loadStageLogs(task.projectId(), task.id());
        if (TEST_CASE_GENERATION.equals(normalizedType)) {
            return caseGenerationStageViews(task, logs);
        }
        java.util.List<StageDefinition> definitions = new java.util.ArrayList<>();
        if (REQUIREMENT_ANALYSIS.equals(normalizedType)) {
            java.util.Set<String> coreNodes = new java.util.LinkedHashSet<>();
            for (InvocationStageLog log : logs) {
                String code = stageCodeFromTaskType(log.taskType());
                if (code != null && code.startsWith("CORE_NODE_")) coreNodes.add(code);
            }
            if (coreNodes.isEmpty()) {
                definitions.add(new StageDefinition("CORE", "理解需求与识别范围"));
            } else {
                for (String code : coreNodes) {
                    definitions.add(new StageDefinition(code, "理解需求片段 " + code.substring("CORE_NODE_".length())));
                }
                if (logs.stream().map(InvocationStageLog::taskType)
                        .anyMatch(type -> type != null && type.toUpperCase().contains("_CORE_DEPENDENCY_MAP"))) {
                    definitions.add(new StageDefinition("CORE_DEPENDENCY_MAP", "建立跨片主题依赖"));
                }
            }
        }
        java.util.Set<String> matrixNodes = new java.util.LinkedHashSet<>();
        for (InvocationStageLog log : logs) {
            String code = stageCodeFromTaskType(log.taskType());
            if (code != null && code.startsWith("COVERAGE_MATRIX_NODE_")) matrixNodes.add(code);
        }
        if (matrixNodes.isEmpty()) {
            definitions.add(new StageDefinition("COVERAGE_MATRIX", "生成覆盖矩阵"));
        } else {
            for (String code : matrixNodes) {
                definitions.add(new StageDefinition(code, "生成覆盖矩阵节点 "
                        + code.substring("COVERAGE_MATRIX_NODE_".length())));
            }
        }
        java.util.Set<String> testPointNodes = new java.util.LinkedHashSet<>();
        for (InvocationStageLog log : logs) {
            String code = stageCodeFromTaskType(log.taskType());
            if (code != null && code.startsWith("TEST_POINTS_")) {
                testPointNodes.add(code);
            }
        }
        if (testPointNodes.isEmpty()) {
            definitions.add(new StageDefinition("TEST_POINTS", "拆解测试点"));
        } else {
            for (String code : testPointNodes) {
                String suffix = code.substring("TEST_POINTS_".length());
                String label = suffix.startsWith("REPAIR_") ? "补齐测试点节点 " + suffix.substring("REPAIR_".length())
                        : "拆解测试点节点 " + suffix;
                definitions.add(new StageDefinition(code, label));
            }
        }
        java.util.Set<String> compositionNodes = new java.util.LinkedHashSet<>();
        for (InvocationStageLog log : logs) {
            String code = stageCodeFromTaskType(log.taskType());
            if (code != null && code.startsWith("CASE_COMPOSITION_NODE_")) {
                compositionNodes.add(code);
            }
        }
        if (compositionNodes.isEmpty()) {
            definitions.add(new StageDefinition("CASE_COMPOSITION", "编排节点用例与完整流程"));
        } else {
            for (String code : compositionNodes) {
                definitions.add(new StageDefinition(code, "编排用例节点 "
                        + code.substring("CASE_COMPOSITION_NODE_".length())));
            }
            if (logs.stream().map(InvocationStageLog::taskType)
                    .anyMatch(type -> type != null && type.toUpperCase().contains("_CASE_COMPOSITION_FLOW"))) {
                definitions.add(new StageDefinition("CASE_COMPOSITION_FLOW", "编排跨主题完整流程"));
            }
        }
        if (REQUIREMENT_ANALYSIS.equals(normalizedType)) {
            definitions.removeIf(definition -> !definition.code().startsWith("CORE"));
        } else if (REQUIREMENT_SCOPE_CONTINUATION.equals(normalizedType)) {
            definitions.removeIf(definition -> definition.code().startsWith("CASE_COMPOSITION")
                    || definition.code().startsWith("CORE"));
        } else if (TEST_POINT_SCOPE_CONTINUATION.equals(normalizedType)) {
            definitions.removeIf(definition -> !definition.code().startsWith("CASE_COMPOSITION"));
        }
        java.util.Map<String, InvocationStageLog> latestByStage = new LinkedHashMap<>();
        for (InvocationStageLog log : logs) {
            String code = stageCodeFromTaskType(log.taskType());
            if (code != null) {
                latestByStage.put(code, log);
            }
        }
        java.util.List<TaskStageView> views = new java.util.ArrayList<>();
        boolean previousSucceeded = true;
        boolean runningAssigned = false;
        for (StageDefinition definition : definitions) {
            InvocationStageLog log = latestByStage.get(definition.code());
            String status;
            String errorCode = null;
            String errorMessage = null;
            LocalDateTime updatedAt = null;
            if (log != null) {
                status = stageStatusFromInvocation(log.status());
                errorCode = log.errorCode();
                errorMessage = log.errorMessage();
                updatedAt = log.createdAt();
            } else if ("RUNNING".equalsIgnoreCase(task.runStatus()) && previousSucceeded && !runningAssigned) {
                status = "RUNNING";
                runningAssigned = true;
            } else if (("FAILED".equalsIgnoreCase(task.runStatus()) || "TIMEOUT".equalsIgnoreCase(task.runStatus()))
                    && previousSucceeded && !runningAssigned) {
                status = task.runStatus();
                errorCode = task.errorCode();
                errorMessage = task.errorMessage();
                runningAssigned = true;
            } else if ("SUCCEEDED".equalsIgnoreCase(task.runStatus()) && previousSucceeded) {
                status = "SUCCEEDED";
            } else {
                status = "PENDING";
            }
            if (!"SUCCEEDED".equals(status)) {
                previousSucceeded = false;
            }
            views.add(new TaskStageView(definition.code(), definition.label(), status, errorCode, errorMessage, updatedAt));
        }
        return views;
    }

    private java.util.List<TaskStageView> caseGenerationStageViews(GenerationTaskRecord task,
                                                                     java.util.List<InvocationStageLog> logs) {
        java.util.Map<String, InvocationStageLog> latest = new LinkedHashMap<>();
        for (InvocationStageLog log : logs) {
            String code = stageCodeFromTaskType(log.taskType());
            if (code != null && code.startsWith("CASE_NODE_")) {
                latest.put(code, log);
            }
        }
        if (latest.isEmpty()) {
            String status = "RUNNING".equalsIgnoreCase(task.runStatus()) ? "RUNNING" : task.runStatus();
            return java.util.List.of(new TaskStageView("CASE_NODE", "生成测试用例", status,
                    task.errorCode(), task.errorMessage(), task.updatedAt()));
        }
        java.util.List<TaskStageView> views = new java.util.ArrayList<>();
        latest.forEach((code, log) -> views.add(new TaskStageView(code,
                "生成用例节点 " + code.substring("CASE_NODE_".length()),
                stageStatusFromInvocation(log.status()), log.errorCode(), log.errorMessage(), log.createdAt())));
        return views;
    }

    private java.util.List<InvocationStageLog> loadStageLogs(Long projectId, Long taskId) {
        if (jdbc == null || projectId == null || taskId == null) {
            return java.util.List.of();
        }
        try {
            return jdbc.sql("""
                    SELECT task_type, status, error_code, error_message, created_at
                    FROM llm_invocation_log
                    WHERE project_id = :projectId AND task_id = :taskId
                    ORDER BY created_at ASC, id ASC
                    """)
                    .param("projectId", projectId)
                    .param("taskId", taskId)
                    .query((rs, rowNum) -> new InvocationStageLog(
                            rs.getString("task_type"),
                            rs.getString("status"),
                            rs.getString("error_code"),
                            rs.getString("error_message"),
                            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime()))
                    .list();
        } catch (RuntimeException ignored) {
            return java.util.List.of();
        }
    }

    private String stageCodeFromTaskType(String taskType) {
        String value = taskType == null ? "" : taskType.toUpperCase();
        java.util.regex.Matcher coreNode = java.util.regex.Pattern.compile("_CORE(?:_REPAIR)?_PART_(\\d+)").matcher(value);
        if (coreNode.find()) return "CORE_NODE_" + coreNode.group(1);
        if (value.contains("_CORE_DEPENDENCY_MAP")) return "CORE_DEPENDENCY_MAP";
        if (value.contains("_CORE")) return "CORE";
        java.util.regex.Matcher matrixNode = java.util.regex.Pattern
                .compile("_COVERAGE_MATRIX(?:_REPAIR)?_NODE_(\\d+)")
                .matcher(value);
        if (matrixNode.find()) return "COVERAGE_MATRIX_NODE_" + matrixNode.group(1);
        if (value.contains("_COVERAGE_MATRIX")) return "COVERAGE_MATRIX";
        java.util.regex.Matcher compositionNode = java.util.regex.Pattern
                .compile("_CASE_COMPOSITION(?:_REPAIR)?_NODE_(\\d+)")
                .matcher(value);
        if (compositionNode.find()) {
            return "CASE_COMPOSITION_NODE_" + compositionNode.group(1);
        }
        if (value.contains("_CASE_COMPOSITION_FLOW")) return "CASE_COMPOSITION_FLOW";
        if (value.contains("_CASE_COMPOSITION")) return "CASE_COMPOSITION";
        java.util.regex.Matcher pointNode = java.util.regex.Pattern
                .compile("_TEST_POINTS(?:_REPAIR)?_(\\d+)")
                .matcher(value);
        if (pointNode.find()) {
            return value.contains("_TEST_POINTS_REPAIR_")
                    ? "TEST_POINTS_REPAIR_" + pointNode.group(1)
                    : "TEST_POINTS_" + pointNode.group(1);
        }
        java.util.regex.Matcher caseNode = java.util.regex.Pattern
                .compile("GENERATION_CASES_NODE_(\\d+)")
                .matcher(value);
        if (caseNode.find()) return "CASE_NODE_" + caseNode.group(1);
        if (value.contains("_TEST_POINTS")) return "TEST_POINTS";
        return null;
    }

    private String stageStatusFromInvocation(String status) {
        String value = status == null ? "" : status.toUpperCase();
        if ("OK".equals(value) || "ATTEMPT_OK".equals(value)) return "SUCCEEDED";
        if ("ATTEMPT_RETRY".equals(value)) return "RUNNING";
        if ("TIMEOUT".equals(value)) return "TIMEOUT";
        if ("INVALID_REQUEST".equals(value) || "MODEL_ERROR".equals(value)
                || "ATTEMPT_FAILED".equals(value) || "QUOTA_EXCEEDED".equals(value)) {
            return "FAILED";
        }
        return value.isBlank() ? "PENDING" : value;
    }

    private String defaultTaskName(String taskType) {
        return switch (taskType) {
            case TEST_CASE_GENERATION -> "异步测试用例生成";
            case TEST_POINT_GENERATION -> "异步测试点生成";
            case TRACE_SUMMARY -> "异步轨迹摘要生成";
            case REQUIREMENT_ANALYSIS -> "异步需求分析";
            case REQUIREMENT_SCOPE_CONTINUATION -> "按确认范围生成测试点";
            case TEST_POINT_SCOPE_CONTINUATION -> "按确认测试点生成编排";
            case INCREMENTAL_CASE_GENERATION -> "异步增量用例更新";
            default -> "异步生成任务";
        };
    }

    private String defaultRequirementText(String taskType, CreateAsyncTaskCommand command) {
        if (TRACE_SUMMARY.equals(taskType)) {
            return "TRACE_SUMMARY:" + command.traceGroupId();
        }
        return command.requirementText();
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String empty(String value) {
        return value == null ? "" : value;
    }

    private String contentHash(String value) {
        String normalized = value == null ? "" : value;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException("生成请求指纹失败");
        }
    }

    private String modelFingerprint(Long modelConfigId) {
        if (modelConfigId == null || modelConfigService == null) {
            return "";
        }
        try {
            ModelConfigService.RuntimeModelConfig config = modelConfigService.getRuntimeConfig(modelConfigId);
            if (config == null) {
                return "";
            }
            return empty(config.provider()) + "|" + empty(config.modelName()) + "|" + empty(config.endpoint());
        } catch (RuntimeException ex) {
            return "modelConfigId=" + modelConfigId;
        }
    }

    private String promptTemplateFingerprint(Long promptTemplateId) {
        if (promptTemplateId == null || promptTemplateService == null) {
            return "";
        }
        try {
            PromptTemplateRecord template = promptTemplateService.getById(promptTemplateId);
            if (template == null) {
                return "promptTemplateId=" + promptTemplateId;
            }
            return "promptTemplateId=" + template.id()
                    + "|version=" + emptyInt(template.version())
                    + "|contentHash=" + empty(template.contentHash());
        } catch (RuntimeException ex) {
            return "promptTemplateId=" + promptTemplateId;
        }
    }

    private String emptyInt(Integer value) {
        return value == null ? "" : value.toString();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        timeoutScheduler.shutdownNow();
    }

    public record CreateAsyncTaskCommand(
            String taskType,
            String taskName,
            String requirementText,
            Long modelConfigId,
            Long promptTemplateId,
            String promptSnapshot,
            String generationMode,
            Boolean useMiniTom,
            Integer promptVersion,
            Long traceGroupId,
            Long traceSessionId,
            Long issueClipId,
            String summaryScope,
            Long sessionId,
            Long analysisId
    ) {
    }

    private record StageDefinition(String code, String label) {
    }

    private record InvocationStageLog(String taskType, String status, String errorCode, String errorMessage,
                                      LocalDateTime createdAt) {
    }

    public record TaskStageView(String code, String label, String status, String errorCode, String errorMessage,
                                LocalDateTime updatedAt) {
    }

    public record TaskView(Long taskId, String taskType, String status, String errorCode, String errorMessage,
                           int draftCount, LocalDateTime createdAt, LocalDateTime updatedAt,
                           java.util.List<TaskStageView> stages) {
        public TaskView(Long taskId, String taskType, String status, String errorCode, String errorMessage,
                        int draftCount, LocalDateTime createdAt, LocalDateTime updatedAt) {
            this(taskId, taskType, status, errorCode, errorMessage, draftCount, createdAt, updatedAt,
                    java.util.List.of());
        }
    }

    private record TraceTaskPayload(Long traceGroupId, Long traceSessionId, Long issueClipId, String summaryScope) {
    }

    private record IncrementalCasePayload(java.util.List<Integer> selectedDraftIds) {
    }

    private record RequirementScopePayload(Long sessionId, Integer analysisVersion, Long analysisId) {
    }

    private record CreateOrReuseResult(GenerationTaskRecord task, boolean created) {
    }
}

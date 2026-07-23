package com.company.aitest.generation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.common.TomUsageMode;
import com.company.aitest.llm.gateway.LlmGateway;
import com.company.aitest.llm.gateway.JsonOutputParser;
import com.company.aitest.llm.gateway.LlmErrorCode;
import com.company.aitest.llm.gateway.LlmInvocationRequest;
import com.company.aitest.llm.gateway.LlmInvocationResponse;
import com.company.aitest.llm.gateway.LlmInvocationStatus;
import com.company.aitest.llm.gateway.LlmRuntimeException;
import com.company.aitest.llm.gateway.LlmStage;
import com.company.aitest.minitom.MiniTomService;
import com.company.aitest.minitom.TestObjectModelRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DirectCaseGenerationService {
    private static final int CASE_GENERATION_MAX_TOKENS = 8192;
    private static final int CASE_TEST_POINT_BATCH_SIZE = 4;
    private static final int CASE_PLAN_NODE_BATCH_SIZE = 4;
    private static final String SYSTEM_PROMPT = """
            你是资深测试工程师。请根据输入需求直接生成功能测试用例草稿。
            你必须严格只返回 JSON 对象，不要输出任何额外解释，不要输出 markdown。
            JSON 对象只允许包含 cases 一个键，格式为：
            {"cases":[{"caseTitle":"...","moduleName":"...","precondition":"...","steps":"1. ...","expectedResult":"1. ...","priority":"P1","caseStrategy":"NODE_FOCUSED/FLOW_COMPOSED","scenarioType":"POSITIVE","designMethods":["场景法"],"designCoverage":["VALID_FLOW"],"sourceCasePlan":"CP1","sourceCaseDesign":"CD1","sourceTestPoint":"...","sourceTestPointRefs":["TP1"],"sourceBasis":["..."],"unsupportedItems":[],"confidence":0.8}]}
            cases 数组每个对象字段固定为：
            caseTitle, moduleName, precondition, steps, expectedResult, priority, caseStrategy, scenarioType, designMethods, designCoverage, sourceCasePlan, sourceCaseDesign, sourceTestPoint, sourceTestPointRefs, sourceBasis, unsupportedItems, confidence

            【要求】
            1. caseTitle：用例名称，简洁概括测试场景。
            2. moduleName：所属模块名。
            3. precondition：前置条件，写明执行用例前需要满足的状态（如"已登录且有权限"、"已有X条数据"），用分号分隔多个条件。
            4. steps：测试步骤，必须按 "1. xxx 2. xxx 3. xxx" 格式，每一步写清楚具体操作对象和动作（如"点击XX按钮"、"在XX输入框输入YY"、"查看XX列表"），不能笼统概括。
            5. expectedResult：预期结果，必须按 "1. xxx 2. xxx 3. xxx" 格式，与 steps 一一对应，每步写明页面应展示的具体状态、字段值或交互反馈（如"列表显示N条记录"、"弹出确认弹窗"、"状态变为已取消"）。
            6. priority：P0/P1/P2/P3/P4。
            7. sourceCasePlan：该用例对应的 CP 用例编排计划编号；计划生成时必须原样返回。
            8. sourceCaseDesign：该用例对应的 CD 用例设计编号；必须原样引用当前计划中的 case_designs.id。
            9. sourceTestPoint：该用例对应的测试点标题，必须来自输入中的测试点或需求目标。
            10. sourceTestPointRefs：数组，填写本用例实际覆盖的 TP 编号；NODE_FOCUSED 可覆盖同一测试主题内一个连贯验证目标的多个 TP，FLOW_COMPOSED 可覆盖多个主题 TP，必须来自当前 CP 计划。
            11. sourceBasis：数组，写明来自需求、TOM、业务包、页面画像、轨迹摘要中的依据名称。
            12. unsupportedItems：数组，写明没有证据支撑但为了可执行性暂时假设的内容；没有则返回空数组。
            13. confidence：0~1 数字。证据不足或 unsupportedItems 非空时不得高于 0.65。
            14. caseStrategy=NODE_FOCUSED 时，已完成的前置测试点只能写入 precondition，步骤与预期只验证当前节点；caseStrategy=FLOW_COMPOSED 时，按测试点依赖顺序覆盖完整端到端流程。不得根据具体业务名称硬编码任何流程。
            15. caseType 固定属于功能用例，但 scenarioType 必须表达场景性质，只允许 POSITIVE/NEGATIVE/BOUNDARY/COMBINATION/STATE/RECOVERY；不能把所有功能用例都标成 POSITIVE。
            16. designMethods 必须列出本条用例实际使用的设计方法；designCoverage 必须列出本条用例实际兑现的覆盖义务。一个 CD 可以返回多条用例共同完成其全部 design_methods 和 coverage_requirements，但不得只返回代表性正向样例。

            steps 和 expectedResult 的条数必须一致，确保每一步都有对应预期。
            不允许生成与输入分析/测试点无关的业务场景；没有证据时宁可输出低置信草稿，也不要编造为事实。
            不得因为测试点较多而只返回代表性样例；当前批次中每个测试点都必须有对应的用例。
            每条用例保持 2~4 个关键步骤和对应预期，确保完整覆盖的同时控制单条长度。

            【测试方法论约束】
            1. 按三层递进设计：先覆盖核心主流程，再覆盖异常/分支，最后补边界、数据一致性、权限、并发、幂等。
            2. 按测试点选择方法：流程用场景法，字段/规则用等价类+边界值，多条件用判定表，状态变化用状态迁移，历史高风险或复杂异常用错误推测。
            3. 防冗余：状态差异但步骤相同要参数化合并；同一表单多个字段校验要聚合；同类入口同一操作只保留一个验证目标；取消/关闭/返回预期一致时不要拆成多条核心用例。
            4. P0 仅用于核心业务 Happy Path；字段校验、权限拦截、取消关闭、样式布局、纯异常路径通常不得标为 P0。
            """;

    private static final String MINI_TOM_SYSTEM_PROMPT_EXTENSION = """

            【Mini-TOM 辅助规则】
            你已获得当前项目已确认的测试对象模型（Mini-TOM）上下文。
            请遵守以下规则：
            1. TOM 上下文中列出的模块、页面、字段、角色、流程、状态是已确认的测试对象，请优先围绕它们设计用例。
            2. 仅使用 status=ACTIVE 的 TOM；忽略 CANDIDATE 或 REJECTED 的对象。
            3. 如果用户需求与 TOM 上下文存在冲突，以用户需求为准，但在用例的 assumptionNote 中说明冲突点。
            4. 如果 TOM 上下文中匹配到的模块/页面/字段在用户需求中未提及，仍然可以生成用例，但标记 isAssumption=1。
            5. 请利用 suggestedAssertions 中的断言建议来设计预期结果。
            6. 如果用户需求涉及的范围超出 TOM 上下文覆盖范围，请正常生成用例，不受 TOM 限制。
            """;

    private final GenerationTaskService generationTaskService;
    private final LlmGateway llmGateway;
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbc;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;
    private final MiniTomService miniTomService;
    private final ClarificationService clarificationService;
    private final com.company.aitest.loop.LoopIntegrationService loopIntegrationService;

    public DirectCaseGenerationService(GenerationTaskService generationTaskService, LlmGateway llmGateway,
                                       JdbcTemplate jdbcTemplate, JdbcClient jdbc, TimeProvider timeProvider,
                                       MiniTomService miniTomService, ClarificationService clarificationService,
                                       com.company.aitest.loop.LoopIntegrationService loopIntegrationService) {
        this.generationTaskService = generationTaskService;
        this.llmGateway = llmGateway;
        this.jdbcTemplate = jdbcTemplate;
        this.jdbc = jdbc;
        this.timeProvider = timeProvider;
        this.objectMapper = new ObjectMapper();
        this.miniTomService = miniTomService;
        this.clarificationService = clarificationService;
        this.loopIntegrationService = loopIntegrationService;
    }

    public GenerateResult generateFromTask(Long projectId, Long taskId, CurrentUser user) {
        GenerationTaskRecord task = generationTaskService.get(projectId, taskId);
        if (task.modelConfigId() == null) {
            throw new BusinessException("任务未配置模型，无法生成用例");
        }
        if (user == null || user.id() == null) {
            throw new BusinessException("缺少调用用户上下文");
        }

        // 检查是否有未回答的反问
        if (clarificationService.hasPendingQuestions(taskId)) {
            var questions = clarificationService.listQuestions(taskId).stream()
                    .filter(q -> "PENDING".equals(q.answerStatus())).toList();
            return new GenerateResult(taskId, null, List.of(), 0, true, questions, List.of());
        }

        String systemPrompt;
        String userPrompt;
        int tomHitCount = 0;
        TomUsageMode tomMode = TomUsageMode.resolve(
                task.generationMode(), Boolean.TRUE.equals(task.useMiniTom()));

        if (tomMode.usesTom()) {
            // --- Mini-TOM assisted generation, scoped by the session's explicit mode. ---
            MiniTomService.TestScopeResult scope = miniTomService.buildTestScope(
                    projectId, task.requirementText(), task.modelConfigId(), user, tomMode);

            String tomContext = buildTomContextString(scope);
            tomHitCount = countTomHits(scope);

            systemPrompt = SYSTEM_PROMPT + MINI_TOM_SYSTEM_PROMPT_EXTENSION;
            userPrompt = """
                    【需求描述】
                    %s

                    %s

                    【补充提示词】
                    %s
                    """.formatted(
                            emptySafe(task.requirementText()),
                            tomContext,
                            emptySafe(task.promptSnapshot()));

            // 持久化 TOM 快照（单独事务）
            persistTomSnapshots(taskId, scope, task);
        } else {
            systemPrompt = SYSTEM_PROMPT;
            userPrompt = """
                    【需求描述】
                    %s

                    【补充提示词】
                    %s
                    """.formatted(emptySafe(task.requirementText()), emptySafe(task.promptSnapshot()));
        }

        List<Map<String, Object>> sourceTestPoints = extractTestPointsFromRequirement(task.requirementText());
        List<Map<String, Object>> casePlan = extractCasePlanFromRequirement(task.requirementText());
        if (!casePlan.isEmpty()) {
            return generateByCasePlanNodes(projectId, task, user, systemPrompt, userPrompt, casePlan,
                    sourceTestPoints, tomHitCount);
        }
        if (!sourceTestPoints.isEmpty()) {
            return generateByTestPointBatches(projectId, task, user, systemPrompt, userPrompt, sourceTestPoints, tomHitCount);
        }

        // 兼容没有结构化测试点的历史任务；新需求分析主链路不会走这个分支。
        LlmInvocationResponse response = llmGateway.invoke(new LlmInvocationRequest(
                null, user.id(), projectId, task.id(),
                "GENERATION", LlmStage.TEST_CASE_GEN,
                task.modelConfigId(), null, null, Map.of(
                        "requirementText", emptySafe(task.requirementText()),
                        "promptSnapshot", emptySafe(task.promptSnapshot()),
                        "useMiniTom", tomMode.usesTom(),
                        "tomMode", tomMode.name(),
                        "tomHitCount", tomHitCount),
                systemPrompt, userPrompt, null, CASE_GENERATION_MAX_TOKENS));

        if (response.status() != LlmInvocationStatus.OK) {
            throw new LlmRuntimeException(parseErrorCode(response.errorCode()),
                    "生成失败：" + emptySafe(response.errorMessage()));
        }
        String output = response.content();

        if (generationTaskService.isCanceled(taskId)) {
            throw new BusinessException("生成任务已取消，结果未保存");
        }

        // 解析 + 持久化草稿（单独事务）
        List<TestCaseDraftView> saved = saveDrafts(projectId, taskId, output, user);
        generationTaskService.touchProgress(taskId);

        // 收集假设
        List<String> assumptions = List.of();
        if (task.assumptionsSnapshot() != null && !task.assumptionsSnapshot().isBlank()) {
            try {
                assumptions = objectMapper.readValue(task.assumptionsSnapshot(), new TypeReference<>() {});
            } catch (Exception ignored) {}
        }

        loopIntegrationService.onGenerationCompleted(projectId, task.requirementText(), null, output, user);
        loopIntegrationService.onChineseLocalizationCheck(projectId, output, "CASE_GENERATION", user);

        return new GenerateResult(task.id(), output, saved, tomHitCount, false, List.of(), assumptions);
    }

    private GenerateResult generateByTestPointBatches(Long projectId,
                                                       GenerationTaskRecord task,
                                                       CurrentUser user,
                                                       String systemPrompt,
                                                       String fullUserPrompt,
                                                       List<Map<String, Object>> testPoints,
                                                       int tomHitCount) {
        List<TestCaseDraftView> allDrafts = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        String context = removeTestPointSection(fullUserPrompt);
        java.util.Set<String> completedSources = completedSourceTestPoints(task.id());
        for (int from = 0, nodeIndex = 1; from < testPoints.size(); from += CASE_TEST_POINT_BATCH_SIZE, nodeIndex++) {
            List<Map<String, Object>> batch = testPoints.subList(from,
                    Math.min(from + CASE_TEST_POINT_BATCH_SIZE, testPoints.size())).stream()
                    .filter(point -> !completedSources.contains(str(point.get("title"), "")))
                    .toList();
            if (batch.isEmpty()) {
                continue;
            }
            String batchJson;
            try {
                batchJson = objectMapper.writeValueAsString(batch);
            } catch (JsonProcessingException e) {
                throw new BusinessException("测试点批次序列化失败");
            }
            String userPrompt = """
                    %s

                    【本节点必须完成的测试点】
                    %s

                    只为本节点的每条测试点生成用例。每条测试点至少一条用例，case.sourceTestPoint 必须原样引用测试点 title；
                    不得输出代表性样例后提前结束，也不得生成下一节点的用例。
                    """.formatted(context, batchJson);
            generationTaskService.touchProgress(task.id());
            LlmInvocationResponse response = llmGateway.invoke(new LlmInvocationRequest(
                    UUID.randomUUID().toString(), user.id(), projectId, task.id(),
                    "GENERATION_CASES_NODE_" + nodeIndex, LlmStage.TEST_CASE_GEN,
                    task.modelConfigId(), null, null, Map.of("nodeIndex", nodeIndex, "testPointCount", batch.size()),
                    systemPrompt, userPrompt, null, CASE_GENERATION_MAX_TOKENS));
            if (response.status() != LlmInvocationStatus.OK) {
                throw new LlmRuntimeException(parseErrorCode(response.errorCode()),
                        "用例生成节点 " + nodeIndex + " 失败：" + emptySafe(response.errorMessage()));
            }
            List<CaseDraftInput> inputs = parseOutput(response.content(), task.id());
            List<String> missing = missingSourceTestPoints(batch, inputs);
            if (!missing.isEmpty()) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                        "用例生成节点 " + nodeIndex + " 未覆盖测试点：" + String.join("、", missing));
            }
            allDrafts.addAll(saveDraftInputs(projectId, task.id(), inputs, user));
            generationTaskService.touchProgress(task.id());
            completedSources.addAll(inputs.stream().map(CaseDraftInput::sourceTestPoint).toList());
            outputs.add(response.content());
        }
        String output = String.join("\n", outputs);
        loopIntegrationService.onGenerationCompleted(projectId, task.requirementText(), null, output, user);
        loopIntegrationService.onChineseLocalizationCheck(projectId, output, "CASE_GENERATION", user);
        return new GenerateResult(task.id(), output, allDrafts, tomHitCount, false, List.of(), List.of());
    }

    /**
     * Batch independent node plans only as a transport optimization.  A FLOW_COMPOSED plan is
     * always isolated, and every response is still validated/persisted by its own CP identity.
     */
    private List<List<Map<String, Object>>> partitionCasePlans(List<Map<String, Object>> casePlan,
                                                                 java.util.Set<String> completedPlans) {
        List<List<Map<String, Object>>> batches = new ArrayList<>();
        List<Map<String, Object>> currentNodeBatch = new ArrayList<>();
        for (Map<String, Object> plan : casePlan) {
            String planId = str(plan.get("id"), "");
            if (planId.isBlank()) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR, "用例编排计划缺少 CP 编号，无法生成可恢复草稿。");
            }
            if (completedPlans.contains(planId)) continue;
            boolean nodeFocused = "NODE_FOCUSED".equals(str(plan.get("case_strategy"), ""));
            if (!nodeFocused) {
                if (!currentNodeBatch.isEmpty()) {
                    batches.add(new ArrayList<>(currentNodeBatch));
                    currentNodeBatch.clear();
                }
                batches.add(List.of(plan));
                continue;
            }
            currentNodeBatch.add(plan);
            if (currentNodeBatch.size() >= CASE_PLAN_NODE_BATCH_SIZE) {
                batches.add(new ArrayList<>(currentNodeBatch));
                currentNodeBatch.clear();
            }
        }
        if (!currentNodeBatch.isEmpty()) batches.add(currentNodeBatch);
        return batches;
    }

    private void generateCasePlanBatch(Long projectId,
                                       GenerationTaskRecord task,
                                       CurrentUser user,
                                       String systemPrompt,
                                       String context,
                                       Map<String, Map<String, Object>> pointsById,
                                       List<Map<String, Object>> plans,
                                       int batchIndex,
                                       List<TestCaseDraftView> allDrafts,
                                       List<String> outputs,
                                       java.util.Set<String> completedPlans) {
        try {
            CasePlanBatchPayload payload = buildCasePlanBatchPayload(plans, pointsById);
            generationTaskService.touchProgress(task.id());
            LlmInvocationResponse response = llmGateway.invoke(new LlmInvocationRequest(
                    UUID.randomUUID().toString(), user.id(), projectId, task.id(),
                    "GENERATION_CASES_BATCH_" + batchIndex, LlmStage.TEST_CASE_GEN,
                    task.modelConfigId(), null, null,
                    Map.of("batchIndex", batchIndex, "casePlanCount", plans.size(), "testPointCount", payload.pointCount()),
                    systemPrompt, buildCasePlanBatchPrompt(context, payload), null, CASE_GENERATION_MAX_TOKENS));
            if (response.status() != LlmInvocationStatus.OK) {
                throw new LlmRuntimeException(parseErrorCode(response.errorCode()),
                        "用例生成批次 " + batchIndex + " 失败：" + emptySafe(response.errorMessage()));
            }
            List<CaseDraftInput> inputs = parseOutput(response.content(), task.id());
            try {
                validateBatchCases(payload, inputs);
            } catch (LlmRuntimeException incomplete) {
                if (incomplete.errorCode() != LlmErrorCode.OUTPUT_PARSE_ERROR) throw incomplete;
                generationTaskService.touchProgress(task.id());
                LlmInvocationResponse repair = llmGateway.invoke(new LlmInvocationRequest(
                        UUID.randomUUID().toString(), user.id(), projectId, task.id(),
                        "GENERATION_CASES_COVERAGE_REPAIR_" + batchIndex, LlmStage.TEST_CASE_GEN,
                        task.modelConfigId(), null, null,
                        Map.of("batchIndex", batchIndex, "repair", true, "casePlanCount", plans.size()),
                        systemPrompt,
                        buildCasePlanBatchPrompt(context, payload)
                                + "\n\n【上轮已生成用例】\n" + response.content()
                                + "\n\n【必须补齐】\n" + incomplete.getMessage()
                                + "\n只返回新增且不重复的补齐用例，不要复写上轮已完成用例。",
                        null, CASE_GENERATION_MAX_TOKENS));
                if (repair.status() != LlmInvocationStatus.OK) {
                    throw new LlmRuntimeException(parseErrorCode(repair.errorCode()),
                            "用例覆盖补齐失败：" + emptySafe(repair.errorMessage()));
                }
                List<CaseDraftInput> repairedInputs = parseOutput(repair.content(), task.id());
                List<CaseDraftInput> merged = mergeDistinctCaseInputs(inputs, repairedInputs);
                validateBatchCases(payload, merged);
                inputs = merged;
                outputs.add(repair.content());
            }
            allDrafts.addAll(saveDraftInputs(projectId, task.id(), inputs, user));
            generationTaskService.touchProgress(task.id());
            completedPlans.addAll(payload.planIds());
            outputs.add(response.content());
        } catch (LlmRuntimeException ex) {
            // A malformed or capacity-limited batch must become smaller durable work units, not
            // a generic fallback. Provider/network failures keep their normal retry semantics.
            if (plans.size() > 1 && ex.errorCode() == LlmErrorCode.OUTPUT_PARSE_ERROR) {
                int middle = plans.size() / 2;
                generateCasePlanBatch(projectId, task, user, systemPrompt, context, pointsById,
                        new ArrayList<>(plans.subList(0, middle)), batchIndex * 10 + 1, allDrafts, outputs, completedPlans);
                generateCasePlanBatch(projectId, task, user, systemPrompt, context, pointsById,
                        new ArrayList<>(plans.subList(middle, plans.size())), batchIndex * 10 + 2, allDrafts, outputs, completedPlans);
                return;
            }
            throw ex;
        }
    }

    private CasePlanBatchPayload buildCasePlanBatchPayload(List<Map<String, Object>> plans,
                                                             Map<String, Map<String, Object>> pointsById) {
        Map<String, List<Map<String, Object>>> pointsByPlan = new java.util.LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> preconditionsByPlan = new java.util.LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> designsByPlan = new java.util.LinkedHashMap<>();
        int pointCount = 0;
        for (Map<String, Object> plan : plans) {
            String planId = str(plan.get("id"), "");
            List<String> pointRefs = listValue(plan.get("source_test_point_refs"));
            List<Map<String, Object>> planPoints = pointRefs.stream().map(pointsById::get)
                    .filter(java.util.Objects::nonNull).toList();
            if (planPoints.isEmpty() || planPoints.size() != pointRefs.size()) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                        "用例编排计划 " + planId + " 引用了不存在的测试点，无法生成。");
            }
            List<Map<String, Object>> designs = readObjectList(plan.get("case_designs"));
            if (designs.isEmpty()) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                        "用例编排计划 " + planId + " 缺少 case_designs，不能生成代表性样例。");
            }
            pointsByPlan.put(planId, planPoints);
            preconditionsByPlan.put(planId, summarizePreconditionPoints(listValue(plan.get("precondition_test_point_refs")), pointsById));
            designsByPlan.put(planId, designs);
            pointCount += planPoints.size();
        }
        return new CasePlanBatchPayload(plans, pointsByPlan, preconditionsByPlan, designsByPlan, pointCount);
    }

    private String buildCasePlanBatchPrompt(String context, CasePlanBatchPayload payload) {
        try {
            return """
                    %s

                    【当前用例编排计划批次】
                    %s

                    【每个计划引用的测试点】
                    %s

                    【每个计划的上游前置状态素材】
                    %s

                    【每个计划必须完成的用例设计项】
                    %s

                    只生成本批次 CP 计划的用例。每条 case.sourceCasePlan 必须原样填写所属计划编号；
                    每个计划中的每个 case_designs.id 至少生成一条对应草稿，case.sourceCaseDesign 必须原样引用该 CD 编号；
                    每个 CD 的 design_methods 和 coverage_requirements 是强制完成清单：可由多条不重复功能用例共同完成，返回 case.designMethods 与 case.designCoverage 标明本条兑现项；
                    每个计划的 source_test_point_refs 都必须至少被一条草稿引用；同一 NODE_FOCUSED 计划中的多个 TP 是一个连贯验证目标，可合并为一条或多条不重复草稿。NODE_FOCUSED 的前序点只能根据“上游前置状态素材”写成可读业务状态的 precondition，
                    不得把 TP/CP/CD 等内部编号写入 precondition、steps 或 expectedResult，也不得把前序节点动作重复写入步骤；
                    FLOW_COMPOSED 按计划依赖顺序生成完整流程。不得输出代表性样例后提前结束，不得生成批次外计划的用例。
                    """.formatted(context,
                    objectMapper.writeValueAsString(payload.plans()),
                    objectMapper.writeValueAsString(payload.pointsByPlan()),
                    objectMapper.writeValueAsString(payload.preconditionsByPlan()),
                    objectMapper.writeValueAsString(payload.designsByPlan()));
        } catch (JsonProcessingException e) {
            throw new BusinessException("用例编排批次序列化失败");
        }
    }

    private void validateBatchCases(CasePlanBatchPayload payload, List<CaseDraftInput> inputs) {
        java.util.Set<String> planIds = new java.util.LinkedHashSet<>(payload.planIds());
        if (inputs.stream().anyMatch(input -> !planIds.contains(input.sourceCasePlan()))) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR, "用例生成批次返回了批次外 sourceCasePlan，结果未入库。");
        }
        for (Map<String, Object> plan : payload.plans()) {
            String planId = str(plan.get("id"), "");
            List<CaseDraftInput> planInputs = inputs.stream()
                    .filter(input -> planId.equals(input.sourceCasePlan())).toList();
            List<Map<String, Object>> planPoints = payload.pointsByPlan().getOrDefault(planId, List.of());
            List<String> qualityProblems = caseExecutionQualityProblems(planInputs);
            if (!qualityProblems.isEmpty()) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                        "用例生成计划节点 " + planId + " 存在不可执行用例："
                                + String.join("、", qualityProblems));
            }
            List<String> missingPoints = missingPlanTestPoints(planPoints, planInputs);
            if (!missingPoints.isEmpty()) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                        "用例生成计划节点 " + planId + " 未覆盖测试点：" + String.join("、", missingPoints));
            }
            java.util.Set<String> allowedRefs = new java.util.LinkedHashSet<>(listValue(plan.get("source_test_point_refs")));
            if (planInputs.stream().flatMap(input -> input.sourceTestPointRefs().stream())
                    .anyMatch(ref -> !allowedRefs.contains(ref))) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                        "用例生成计划节点 " + planId + " 返回了计划外测试点引用，结果未入库。");
            }
            List<String> missingDesigns = missingCaseDesigns(payload.designsByPlan().getOrDefault(planId, List.of()), planInputs);
            if (!missingDesigns.isEmpty()) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                        "用例生成计划节点 " + planId + " 未覆盖用例设计项：" + String.join("、", missingDesigns));
            }
            List<String> missingObligations = missingDesignObligations(
                    payload.designsByPlan().getOrDefault(planId, List.of()), planInputs);
            if (!missingObligations.isEmpty()) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                        "用例生成计划节点 " + planId + " 未完成测试方法或覆盖义务："
                                + String.join("、", missingObligations));
            }
        }
    }

    private List<String> caseExecutionQualityProblems(List<CaseDraftInput> cases) {
        List<String> problems = new ArrayList<>();
        java.util.regex.Pattern internalRef = java.util.regex.Pattern.compile("(?i)\\b(?:TP|CP|CD)\\d+\\b");
        for (CaseDraftInput item : cases) {
            String label = item.caseTitle().isBlank() ? "未命名用例" : item.caseTitle();
            int stepCount = countActionLines(item.steps());
            int expectedCount = countActionLines(item.expectedResult());
            if (stepCount == 0 || expectedCount == 0 || stepCount != expectedCount) {
                problems.add(label + "/步骤与预期未一一对应");
            }
            if (item.sourceTestPointRefs().isEmpty()) {
                problems.add(label + "/缺少测试点来源");
            }
            if (item.sourceBasis().isEmpty()) {
                problems.add(label + "/缺少需求或资产依据");
            }
            if (item.designMethods().isEmpty() || item.designCoverage().isEmpty()) {
                problems.add(label + "/缺少设计方法或覆盖声明");
            }
            String visibleText = item.precondition() + "\n" + item.steps() + "\n" + item.expectedResult();
            if (internalRef.matcher(visibleText).find()) {
                problems.add(label + "/用户可见内容包含内部编排编号");
            }
        }
        return problems.stream().distinct().toList();
    }

    private List<String> missingDesignObligations(List<Map<String, Object>> designs, List<CaseDraftInput> cases) {
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> design : designs) {
            String designId = str(design.get("id"), "");
            List<CaseDraftInput> designCases = cases.stream()
                    .filter(item -> designId.equals(item.sourceCaseDesign())).toList();
            java.util.Set<String> methods = designCases.stream().flatMap(item -> item.designMethods().stream())
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            java.util.Set<String> coverage = designCases.stream().flatMap(item -> item.designCoverage().stream())
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            List<String> requiredMethods = listValue(design.get("design_methods"));
            List<String> requiredCoverage = listValue(design.get("coverage_requirements"));
            requiredMethods.stream().filter(item -> !methods.contains(item))
                    .forEach(item -> missing.add(designId + "/方法:" + item));
            requiredCoverage.stream().filter(item -> !coverage.contains(item))
                    .forEach(item -> missing.add(designId + "/覆盖:" + item));
            String requiredScenario = FunctionalTestDesignPolicy.normalizeScenarioType(str(design.get("scenario_type"), "POSITIVE"));
            if (!designCases.isEmpty() && designCases.stream().noneMatch(item -> requiredScenario.equals(item.scenarioType()))) {
                missing.add(designId + "/场景:" + requiredScenario);
            }
        }
        return missing;
    }

    private record CasePlanBatchPayload(List<Map<String, Object>> plans,
                                        Map<String, List<Map<String, Object>>> pointsByPlan,
                                        Map<String, List<Map<String, Object>>> preconditionsByPlan,
                                        Map<String, List<Map<String, Object>>> designsByPlan,
                                        int pointCount) {
        List<String> planIds() {
            return plans.stream().map(plan -> String.valueOf(plan.get("id"))).toList();
        }
    }

    /**
     * 新主链路：每个 case_plan 是一个可独立重试的生成节点。计划引用的前置测试点只作为前置条件，
     * FLOW_COMPOSED 计划才生成端到端组合用例，避免把每条用例重复扩成完整业务流程。
     */
    private GenerateResult generateByCasePlanNodes(Long projectId,
                                                   GenerationTaskRecord task,
                                                   CurrentUser user,
                                                   String systemPrompt,
                                                   String fullUserPrompt,
                                                   List<Map<String, Object>> casePlan,
                                                   List<Map<String, Object>> allTestPoints,
                                                   int tomHitCount) {
        Map<String, Map<String, Object>> pointsById = new java.util.LinkedHashMap<>();
        for (Map<String, Object> point : allTestPoints) {
            String id = str(point.get("id"), "");
            if (!id.isBlank()) pointsById.put(id, point);
        }
        List<TestCaseDraftView> allDrafts = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        java.util.Set<String> completedPlans = completedSourceCasePlans(task.id(), casePlan);
        String context = removeSection(removeTestPointSection(fullUserPrompt), "## 用例编排计划");

        int batchIndex = 1;
        for (List<Map<String, Object>> batch : partitionCasePlans(casePlan, completedPlans)) {
            generateCasePlanBatch(projectId, task, user, systemPrompt, context, pointsById, batch,
                    batchIndex++, allDrafts, outputs, completedPlans);
        }
        String output = String.join("\n", outputs);
        loopIntegrationService.onGenerationCompleted(projectId, task.requirementText(), null, output, user);
        loopIntegrationService.onChineseLocalizationCheck(projectId, output, "CASE_GENERATION", user);
        return new GenerateResult(task.id(), output, allDrafts, tomHitCount, false, List.of(), List.of());
    }

    /**
     * Internal TP ids are useful for traceability but are not valid user-facing preconditions.
     * Pass a compact, business-readable summary to the case writer without widening the current
     * plan's source-test-point coverage.
     */
    private List<Map<String, Object>> summarizePreconditionPoints(List<String> refs,
                                                                    Map<String, Map<String, Object>> pointsById) {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (String ref : refs) {
            Map<String, Object> point = pointsById.get(ref);
            if (point == null) continue;
            Map<String, Object> summary = new java.util.LinkedHashMap<>();
            summary.put("trace_ref", ref);
            copyIfPresent(point, summary, "title");
            copyIfPresent(point, summary, "description");
            copyIfPresent(point, summary, "test_unit_ref");
            copyIfPresent(point, summary, "requirement_refs");
            copyIfPresent(point, summary, "source_basis");
            summaries.add(summary);
        }
        return summaries;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) target.put(key, value);
    }

    @Transactional
    protected void persistTomSnapshots(Long taskId, MiniTomService.TestScopeResult scope, GenerationTaskRecord task) {
        String tomContextSnapshotJson = null;
        String testScopeSnapshotJson = null;
        String projectTomSnapshot = null;
        String systemTomSnapshot = null;
        try {
            tomContextSnapshotJson = objectMapper.writeValueAsString(scope);
            testScopeSnapshotJson = objectMapper.writeValueAsString(scope);
            var projectToms = scope.tomEvidence.stream().filter(e -> "PROJECT".equals(e.scope())).toList();
            var systemToms = scope.tomEvidence.stream().filter(e -> "SYSTEM".equals(e.scope())).toList();
            projectTomSnapshot = objectMapper.writeValueAsString(projectToms);
            systemTomSnapshot = objectMapper.writeValueAsString(systemToms);
        } catch (JsonProcessingException ignored) {}

        jdbcTemplate.update("""
                UPDATE generation_task SET
                    mini_tom_context_snapshot = ?, test_scope_snapshot = ?, tom_hit_count = ?,
                    project_tom_snapshot = ?, system_tom_snapshot = ?,
                    clarification_questions_snapshot = ?, clarification_answers_snapshot = ?,
                    assumptions_snapshot = ?, updated_at = ?
                WHERE id = ?
                """, tomContextSnapshotJson, testScopeSnapshotJson,
                countTomHits(scope), projectTomSnapshot, systemTomSnapshot,
                null, null, task.assumptionsSnapshot(),
                timeProvider.now(), taskId);
    }

    @Transactional
    protected List<TestCaseDraftView> saveDrafts(Long projectId, Long taskId, String llmOutput, CurrentUser user) {
        List<CaseDraftInput> parsed = parseOutput(llmOutput, taskId);
        return saveDraftInputs(projectId, taskId, parsed, user);
    }

    private List<TestCaseDraftView> saveDraftInputs(Long projectId, Long taskId, List<CaseDraftInput> parsed, CurrentUser user) {
        LocalDateTime now = timeProvider.now();
        List<TestCaseDraftView> saved = new ArrayList<>();
        int i = jdbc.sql("SELECT COUNT(*) FROM test_case_draft WHERE task_id = :taskId")
                .param("taskId", taskId).query(Integer.class).single() + 1;
        for (CaseDraftInput item : parsed) {
            String caseNo = "TC-" + taskId + "-" + i++;
            jdbcTemplate.update("""
                    insert into test_case_draft(task_id, project_id, test_point_id, case_no, case_title, project_name,
                      module_name, precondition, steps, expected_result, priority, case_type, scenario_type, design_method,
                      source_refs_json, is_assumption, assumption_note, compliance_mark, user_feedback, quality_status,
                      version_no, asset_status, case_scope, case_status, created_by, created_at, updated_at)
                    values (?, ?, null, ?, ?, null, ?, ?, ?, ?, ?, 'FUNCTIONAL', ?, ?, ?, 0, null, 'UNMARKED',
                      null, ?, 1, 'DRAFT', 'PERSONAL', 'DRAFT', ?, ?, ?)
                    """, taskId, projectId, caseNo, item.caseTitle(), item.moduleName(), item.precondition(),
                    item.steps(), item.expectedResult(), normalizePriority(item.priority()),
                    item.scenarioType(), String.join(" + ", item.designMethods()),
                    buildSourceRefsJson(taskId, item), draftQualityStatus(item), user.id(), now, now);
            Long id = jdbc.sql("select last_insert_id()").query(Long.class).single();
            saved.add(getDraftById(id));
        }
        return saved;
    }

    private List<Map<String, Object>> extractTestPointsFromRequirement(String requirementText) {
        if (requirementText == null) return List.of();
        int marker = requirementText.indexOf("## 测试点");
        if (marker < 0) return List.of();
        int arrayStart = requirementText.indexOf('[', marker);
        if (arrayStart < 0) return List.of();
        int arrayEnd = matchingJsonArrayEnd(requirementText, arrayStart);
        if (arrayEnd < 0) return List.of();
        try {
            return objectMapper.readValue(requirementText.substring(arrayStart, arrayEnd + 1), new TypeReference<>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> extractCasePlanFromRequirement(String requirementText) {
        return extractJsonArraySection(requirementText, "## 用例编排计划", "case_plan");
    }

    private List<Map<String, Object>> extractJsonArraySection(String text, String markerText, String propertyName) {
        if (text == null) return List.of();
        int marker = text.indexOf(markerText);
        if (marker < 0) return List.of();
        int property = text.indexOf("\"" + propertyName + "\"", marker);
        int arrayStart = text.indexOf('[', property >= 0 ? property : marker);
        if (arrayStart < 0) return List.of();
        int arrayEnd = matchingJsonArrayEnd(text, arrayStart);
        if (arrayEnd < 0) return List.of();
        try {
            return objectMapper.readValue(text.substring(arrayStart, arrayEnd + 1), new TypeReference<>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private int matchingJsonArrayEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '[') depth++;
            else if (c == ']' && --depth == 0) return i;
        }
        return -1;
    }

    private String removeTestPointSection(String prompt) {
        return removeSection(prompt, "## 测试点");
    }

    private String removeSection(String prompt, String markerText) {
        if (prompt == null) return "";
        int start = prompt.indexOf(markerText);
        if (start < 0) return prompt;
        int next = prompt.indexOf("\n## ", start + 1);
        return next < 0 ? prompt.substring(0, start) : prompt.substring(0, start) + prompt.substring(next);
    }

    private List<String> missingSourceTestPoints(List<Map<String, Object>> testPoints, List<CaseDraftInput> cases) {
        List<String> sources = cases.stream().map(CaseDraftInput::sourceTestPoint).toList();
        return testPoints.stream().map(point -> str(point.get("title"), ""))
                .filter(title -> !title.isBlank())
                .filter(title -> sources.stream().noneMatch(source -> source.equals(title)))
                .toList();
    }

    private List<String> missingPlanTestPoints(List<Map<String, Object>> testPoints, List<CaseDraftInput> cases) {
        java.util.Set<String> coveredIds = cases.stream()
                .flatMap(input -> input.sourceTestPointRefs().stream())
                .collect(java.util.stream.Collectors.toSet());
        return testPoints.stream()
                .filter(point -> !coveredIds.contains(str(point.get("id"), "")))
                .map(point -> str(point.get("id"), str(point.get("title"), "")))
                .filter(id -> !id.isBlank())
                .toList();
    }

    private List<String> missingCaseDesigns(List<Map<String, Object>> designs, List<CaseDraftInput> cases) {
        List<String> completed = cases.stream().map(CaseDraftInput::sourceCaseDesign).toList();
        return designs.stream().map(design -> str(design.get("id"), ""))
                .filter(id -> !id.isBlank())
                .filter(id -> completed.stream().noneMatch(id::equals))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readObjectList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                rows.add((Map<String, Object>) map);
            }
        }
        return rows;
    }

    private java.util.Set<String> completedSourceTestPoints(Long taskId) {
        java.util.Set<String> sources = new java.util.LinkedHashSet<>();
        List<String> refs = jdbc.sql("SELECT source_refs_json FROM test_case_draft WHERE task_id = :taskId")
                .param("taskId", taskId).query(String.class).list();
        for (String ref : refs) {
            try {
                var node = objectMapper.readTree(ref);
                String source = node.path("sourceTestPoint").asText("");
                if (!source.isBlank()) {
                    sources.add(source);
                }
                var sourceRefs = node.path("sourceTestPointRefs");
                if (sourceRefs.isArray()) {
                    sourceRefs.forEach(item -> {
                        String refId = item.asText("");
                        if (!refId.isBlank()) sources.add(refId);
                    });
                }
            } catch (Exception ignored) {
                // Historical drafts without source refs cannot be used as a resume checkpoint.
            }
        }
        return sources;
    }

    private java.util.Set<String> completedSourceCasePlans(Long taskId, List<Map<String, Object>> casePlans) {
        List<String> refs = jdbc.sql("SELECT source_refs_json FROM test_case_draft WHERE task_id = :taskId")
                .param("taskId", taskId).query(String.class).list();
        return completedSourceCasePlansFromRefs(refs, casePlans);
    }

    private java.util.Set<String> completedSourceCasePlansFromRefs(List<String> refs,
                                                                    List<Map<String, Object>> casePlans) {
        Map<String, java.util.Set<String>> coveredPointsByPlan = new java.util.LinkedHashMap<>();
        Map<String, java.util.Set<String>> coveredDesignsByPlan = new java.util.LinkedHashMap<>();
        Map<String, java.util.Set<String>> coveredMethodsByDesign = new java.util.LinkedHashMap<>();
        Map<String, java.util.Set<String>> coveredRequirementsByDesign = new java.util.LinkedHashMap<>();
        for (String ref : refs) {
            try {
                var node = objectMapper.readTree(ref);
                String plan = node.path("sourceCasePlan").asText("");
                if (plan.isBlank()) continue;
                var pointRefs = coveredPointsByPlan.computeIfAbsent(plan, ignored -> new java.util.LinkedHashSet<>());
                var sourcePointRefs = node.path("sourceTestPointRefs");
                if (sourcePointRefs.isArray()) {
                    sourcePointRefs.forEach(item -> {
                        String pointId = item.asText("");
                        if (!pointId.isBlank()) pointRefs.add(pointId);
                    });
                }
                String design = node.path("sourceCaseDesign").asText("");
                if (!design.isBlank()) {
                    coveredDesignsByPlan.computeIfAbsent(plan, ignored -> new java.util.LinkedHashSet<>()).add(design);
                    String key = plan + "|" + design;
                    addJsonArrayValues(node.path("designMethods"), coveredMethodsByDesign
                            .computeIfAbsent(key, ignored -> new java.util.LinkedHashSet<>()));
                    addJsonArrayValues(node.path("designCoverage"), coveredRequirementsByDesign
                            .computeIfAbsent(key, ignored -> new java.util.LinkedHashSet<>()));
                }
            } catch (Exception ignored) {
                // Historical drafts cannot be used as a case-plan checkpoint.
            }
        }
        java.util.Set<String> completed = new java.util.LinkedHashSet<>();
        for (Map<String, Object> plan : casePlans) {
            String planId = str(plan.get("id"), "");
            if (planId.isBlank()) continue;
            java.util.Set<String> requiredPoints = new java.util.LinkedHashSet<>(listValue(plan.get("source_test_point_refs")));
            java.util.Set<String> requiredDesigns = readObjectList(plan.get("case_designs")).stream()
                    .map(item -> str(item.get("id"), ""))
                    .filter(id -> !id.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            boolean obligationsComplete = readObjectList(plan.get("case_designs")).stream().allMatch(design -> {
                String designId = str(design.get("id"), "");
                String key = planId + "|" + designId;
                return coveredMethodsByDesign.getOrDefault(key, java.util.Set.of())
                                .containsAll(listValue(design.get("design_methods")))
                        && coveredRequirementsByDesign.getOrDefault(key, java.util.Set.of())
                                .containsAll(listValue(design.get("coverage_requirements")));
            });
            if (!requiredPoints.isEmpty()
                    && !requiredDesigns.isEmpty()
                    && coveredPointsByPlan.getOrDefault(planId, java.util.Set.of()).containsAll(requiredPoints)
                    && coveredDesignsByPlan.getOrDefault(planId, java.util.Set.of()).containsAll(requiredDesigns)
                    && obligationsComplete) {
                completed.add(planId);
            }
        }
        return completed;
    }

    private void addJsonArrayValues(com.fasterxml.jackson.databind.JsonNode node, java.util.Set<String> target) {
        if (!node.isArray()) return;
        node.forEach(item -> {
            String value = item.asText("");
            if (!value.isBlank()) target.add(value);
        });
    }

    public List<TestCaseDraftView> listDrafts(Long projectId, Long taskId) {
        generationTaskService.get(projectId, taskId);
        return jdbc.sql("""
                select id, task_id, case_no, case_title, module_name, precondition, steps, expected_result, priority, created_at
                from test_case_draft
                where project_id = :projectId and task_id = :taskId
                order by id asc
                """)
                .param("projectId", projectId)
                .param("taskId", taskId)
                .query(this::mapDraft)
                .list();
    }

    private TestCaseDraftView getDraftById(Long id) {
        return jdbc.sql("""
                select id, task_id, case_no, case_title, module_name, precondition, steps, expected_result, priority, created_at
                from test_case_draft where id = :id
                """)
                .param("id", id)
                .query(this::mapDraft)
                .single();
    }

    private TestCaseDraftView mapDraft(ResultSet rs, int rowNum) throws SQLException {
        return new TestCaseDraftView(
                rs.getLong("id"),
                rs.getLong("task_id"),
                rs.getString("case_no"),
                rs.getString("case_title"),
                rs.getString("module_name"),
                rs.getString("precondition"),
                rs.getString("steps"),
                rs.getString("expected_result"),
                rs.getString("priority"),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }

    // =====================================================================
    // Mini-TOM 上下文构建
    // =====================================================================

    private String buildTomContextString(MiniTomService.TestScopeResult scope) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前项目已确认 Mini-TOM 测试范围】\n\n");

        appendTomSection(sb, "影响模块", scope.affectedModules);
        appendTomSection(sb, "影响页面", scope.affectedPages);
        appendTomSection(sb, "影响字段", scope.affectedFields);
        appendTomSection(sb, "影响角色", scope.affectedRoles);
        appendTomSection(sb, "影响流程", scope.affectedFlows);
        appendTomSection(sb, "影响状态", scope.affectedStates);
        appendTomSection(sb, "建议断言", scope.suggestedAssertions);

        if (scope.suggestedQuestions != null && !scope.suggestedQuestions.isEmpty()) {
            sb.append("\n【建议反问】\n");
            for (String q : scope.suggestedQuestions) {
                sb.append("- ").append(q).append("\n");
            }
        }

        if (scope.unsuggestedExtensions != null && !scope.unsuggestedExtensions.isEmpty()) {
            sb.append("\n【不建议扩展范围】\n");
            for (String e : scope.unsuggestedExtensions) {
                sb.append("- ").append(e).append("\n");
            }
        }

        return sb.toString();
    }

    private void appendTomSection(StringBuilder sb, String title, List<TestObjectModelRecord> toms) {
        if (toms == null || toms.isEmpty()) return;
        sb.append("### ").append(title).append("\n");
        for (TestObjectModelRecord tom : toms) {
            sb.append("- ").append(tom.name());
            if (tom.description() != null && !tom.description().isBlank()) {
                sb.append("：").append(tom.description());
            }
            sb.append("\n");
        }
    }

    private int countTomHits(MiniTomService.TestScopeResult scope) {
        int count = 0;
        if (scope.affectedModules != null) count += scope.affectedModules.size();
        if (scope.affectedPages != null) count += scope.affectedPages.size();
        if (scope.affectedFields != null) count += scope.affectedFields.size();
        if (scope.affectedRoles != null) count += scope.affectedRoles.size();
        if (scope.affectedFlows != null) count += scope.affectedFlows.size();
        if (scope.affectedStates != null) count += scope.affectedStates.size();
        if (scope.suggestedAssertions != null) count += scope.suggestedAssertions.size();
        return count;
    }

    // =====================================================================
    // 输出解析
    // =====================================================================

    private List<CaseDraftInput> parseOutput(String rawOutput, Long taskId) {
        String jsonText = JsonOutputParser.extractJson(rawOutput);
        try {
            List<Map<String, Object>> rows = readCaseRows(jsonText);
            List<CaseDraftInput> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String caseTitle = str(row.get("caseTitle"), "");
                String steps = normalizeNumberedActions(str(row.get("steps"), ""));
                String expectedResult = normalizeNumberedActions(str(row.get("expectedResult"), ""));
                if (caseTitle.isBlank() || steps.isBlank() || expectedResult.isBlank()) {
                    throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR,
                            "模型返回用例缺少 caseTitle、steps 或 expectedResult");
                }
                result.add(new CaseDraftInput(
                        caseTitle,
                        str(row.get("moduleName"), "默认模块"),
                        str(row.get("precondition"), ""),
                        steps,
                        expectedResult,
                        str(row.get("priority"), "P2"),
                        FunctionalTestDesignPolicy.normalizeScenarioType(str(row.get("scenarioType"), "POSITIVE")),
                        normalizedDesignMethods(row),
                        listValue(row.get("designCoverage")),
                        str(row.get("sourceCasePlan"), ""),
                        str(row.get("sourceCaseDesign"), ""),
                        str(row.get("sourceTestPoint"), ""),
                        listValue(row.get("sourceTestPointRefs")),
                        listValue(row.get("sourceBasis")),
                        listValue(row.get("unsupportedItems")),
                        confidenceValue(row.get("confidence"))
                ));
            }
            if (result.isEmpty()) {
                throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR, "模型未返回可用用例");
            }
            return result;
        } catch (JsonProcessingException ex) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR, "模型输出不是有效的用例 JSON");
        }
    }

    private List<Map<String, Object>> readCaseRows(String jsonText) throws JsonProcessingException {
        var root = objectMapper.readTree(jsonText);
        if (root.isArray()) {
            return objectMapper.convertValue(root, new TypeReference<>() {});
        }
        if (root.isObject() && root.has("cases") && root.get("cases").isArray()) {
            return objectMapper.convertValue(root.get("cases"), new TypeReference<>() {});
        }
        throw new JsonProcessingException("模型未返回 cases 数组") {};
    }

    /** Keep every model-provided step, but make inline numbered actions readable in the draft UI. */
    private String normalizeNumberedActions(String value) {
        if (value == null || value.isBlank()) return "";
        return value.trim()
                .replaceAll("(?:[；;]|\\s)+(?=\\d+[.、)]\\s*)", "\n")
                .replaceAll("\\r?\\n[ \\t]+", "\n");
    }

    private String normalizePriority(String value) {
        if (value == null) return "P2";
        String v = value.trim().toUpperCase();
        return switch (v) {
            case "P0", "P1", "P2", "P3", "P4" -> v;
            default -> "P2";
        };
    }

    private List<String> normalizedDesignMethods(Map<String, Object> row) {
        List<String> methods = listValue(row.get("designMethods"));
        if (!methods.isEmpty()) return methods.stream()
                .map(FunctionalTestDesignPolicy::normalizeDesignMethod).distinct().toList();
        String legacy = str(row.get("designMethod"), "场景法");
        return List.of(FunctionalTestDesignPolicy.normalizeDesignMethod(legacy));
    }

    private List<CaseDraftInput> mergeDistinctCaseInputs(List<CaseDraftInput> original,
                                                          List<CaseDraftInput> supplements) {
        Map<String, CaseDraftInput> unique = new java.util.LinkedHashMap<>();
        java.util.stream.Stream.concat(original.stream(), supplements.stream()).forEach(item -> {
            String key = item.sourceCasePlan() + "|" + item.sourceCaseDesign() + "|"
                    + item.caseTitle().trim() + "|" + item.steps().trim();
            unique.putIfAbsent(key, item);
        });
        return new ArrayList<>(unique.values());
    }

    private String str(Object value, String defaultValue) {
        if (value == null) return defaultValue;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private List<String> listValue(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        String text = str(value, "");
        if (text.isBlank()) {
            return List.of();
        }
        return List.of(text);
    }

    private double confidenceValue(Object value) {
        if (value instanceof Number number) {
            return Math.max(0.0, Math.min(1.0, number.doubleValue()));
        }
        try {
            return Math.max(0.0, Math.min(1.0, Double.parseDouble(String.valueOf(value))));
        } catch (Exception ignored) {
            return 0.5;
        }
    }

    private String draftQualityStatus(CaseDraftInput item) {
        if (item == null) {
            return "LOW_EVIDENCE";
        }
        if (item.sourceBasis().isEmpty() || !item.unsupportedItems().isEmpty() || item.confidence() < 0.65
                || item.steps().isBlank() || item.expectedResult().isBlank()
                || countActionLines(item.steps()) != countActionLines(item.expectedResult())
                || item.caseTitle().contains("模型返回原文") || item.moduleName().contains("默认模块")) {
            return "LOW_EVIDENCE";
        }
        if (item.sourceTestPoint().isBlank() || item.confidence() < 0.8) {
            return "PARTIAL";
        }
        return "PASS";
    }

    private String emptySafe(String value) {
        return value == null ? "" : value;
    }

    private LlmErrorCode parseErrorCode(String value) {
        if (value == null || value.isBlank()) {
            return LlmErrorCode.UNKNOWN_ERROR;
        }
        try {
            return LlmErrorCode.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return LlmErrorCode.UNKNOWN_ERROR;
        }
    }

    private String buildSourceRefsJson(Long taskId, CaseDraftInput item) {
        try {
            Map<String, Object> refs = new java.util.LinkedHashMap<>();
            refs.put("source", "LLM_DIRECT");
            refs.put("taskId", taskId);
            refs.put("sourceCasePlan", item.sourceCasePlan());
            refs.put("sourceCaseDesign", item.sourceCaseDesign());
            refs.put("scenarioType", item.scenarioType());
            refs.put("designMethods", item.designMethods());
            refs.put("designCoverage", item.designCoverage());
            refs.put("sourceTestPoint", item.sourceTestPoint());
            refs.put("sourceTestPointRefs", item.sourceTestPointRefs());
            refs.put("sourceBasis", item.sourceBasis());
            refs.put("unsupportedItems", item.unsupportedItems());
            refs.put("confidence", item.confidence());
            refs.put("stepCount", countActionLines(item.steps()));
            refs.put("expectedCount", countActionLines(item.expectedResult()));
            return objectMapper.writeValueAsString(refs);
        } catch (Exception ignored) {
            return "{\"source\":\"LLM_DIRECT\",\"taskId\":" + taskId + "}";
        }
    }

    private int countActionLines(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^\\s*\\d+[\\.、)]\\s*\\S+")
                .matcher(text);
        int numbered = 0;
        while (matcher.find()) {
            numbered++;
        }
        if (numbered > 0) {
            return numbered;
        }
        return (int) java.util.Arrays.stream(text.split("\\R+"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .count();
    }

    record CaseDraftInput(String caseTitle, String moduleName, String precondition, String steps,
                          String expectedResult, String priority, String scenarioType,
                          List<String> designMethods, List<String> designCoverage,
                          String sourceCasePlan, String sourceCaseDesign, String sourceTestPoint,
                          List<String> sourceTestPointRefs, List<String> sourceBasis, List<String> unsupportedItems, double confidence) {
    }

    public record TestCaseDraftView(Long id, Long taskId, String caseNo, String caseTitle, String moduleName,
                                    String precondition, String steps, String expectedResult, String priority,
                                    LocalDateTime createdAt) {
    }

    public record GenerateResult(Long taskId, String llmRawOutput, List<TestCaseDraftView> drafts, int tomHitCount,
                                  boolean needClarification, List<ClarificationService.ClarificationQuestion> questions,
                                  List<String> assumptions) {
    }
}

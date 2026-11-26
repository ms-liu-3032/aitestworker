package com.company.aitest.dsh;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.company.aitest.common.BusinessException;
import com.company.aitest.generation.FunctionalTestDesignPolicy;
import com.fasterxml.jackson.databind.JsonNode;

/** Checks the same functional design obligations used by AITestWork's Web generation path, without invoking an LLM. */
final class DshMethodologyPolicy {
    private static final Set<String> POINT_TYPES = Set.of("MAIN_FLOW", "BRANCH", "BOUNDARY", "EXCEPTION",
            "STATE", "DATA", "AUTH", "CONCURRENCY", "IDEMPOTENT");
    private static final Set<String> SCOPES = Set.of("GENERATE", "REFERENCE_ONLY", "EXCLUDED");
    private static final Set<String> SCENARIOS = Set.of("POSITIVE", "NEGATIVE", "BOUNDARY",
            "COMBINATION", "STATE", "RECOVERY");
    private static final Pattern NUMBERED_ACTION = Pattern.compile("^\\s*\\d+[.、)]\\s*\\S.*$");
    private static final Pattern MODULE_PATH = Pattern.compile("^/[^/\\s]+(?:/[^/\\s]+)*$");
    private static final Pattern INTERNAL_REF = Pattern.compile("(?i)\\b(?:TP|CP|CD)\\d+\\b");
    private static final Pattern PRIOR_CASE_DEPENDENCY = Pattern.compile(
            "(?:上一条|前一条|上一个|前一个|前面|之前|第\\d+条).{0,12}(?:测试)?用例"
            + "|(?:测试)?用例.{0,12}(?:执行|通过|完成).{0,5}(?:后|结果)");
    private static final Set<String> GENERIC_EXPECTATIONS = Set.of("成功", "操作成功", "页面正常", "显示正常",
            "功能正常", "符合预期", "结果正确", "校验通过", "选择成功", "保存成功", "成功保存");

    private DshMethodologyPolicy() {}

    static void validatePoints(JsonNode payload) {
        Map<String, JsonNode> points = points(payload);
        if (points.values().stream().noneMatch(point -> "GENERATE".equals(text(point, "scopeDecision")))) {
            throw new BusinessException("至少保留一个需要生成用例的测试点");
        }
    }

    static void validateCoverage(JsonNode payload, JsonNode pointPayload) {
        Map<String, JsonNode> points = points(pointPayload);
        Map<String, JsonNode> items = new LinkedHashMap<>();
        for (JsonNode item : payload.path("items")) {
            String id = requiredText(item, "pointId");
            JsonNode point = points.get(id);
            if (point == null || !"GENERATE".equals(text(point, "scopeDecision")) || items.putIfAbsent(id, item) != null) {
                throw new BusinessException("覆盖计划必须逐一对应本轮待生成测试点：" + id);
            }
            String pointType = text(point, "pointType");
            requireAll(strings(item, "designMethods"), FunctionalTestDesignPolicy.designMethods(pointType, null),
                    "测试点 " + id + " 缺少设计方法");
            requireAll(strings(item, "coverageRequirements"), FunctionalTestDesignPolicy.coverageRequirements(pointType),
                    "测试点 " + id + " 缺少覆盖义务");
        }
        for (var entry : points.entrySet()) {
            if ("GENERATE".equals(text(entry.getValue(), "scopeDecision")) && !items.containsKey(entry.getKey())) {
                throw new BusinessException("覆盖计划遗漏测试点：" + entry.getKey());
            }
        }
    }

    static void validateCases(JsonNode payload, JsonNode pointPayload, JsonNode coveragePayload) {
        validateCasesForPoints(payload, pointPayload, coveragePayload, null);
    }

    static void validateCasesForPoints(JsonNode payload, JsonNode pointPayload, JsonNode coveragePayload,
                                       Set<String> selectedPointIds) {
        validateCoverage(coveragePayload, pointPayload);
        if (!payload.path("cases").isArray() || payload.path("cases").isEmpty()) {
            throw new BusinessException("用例设计必须包含非空 cases 数组");
        }
        Map<String, JsonNode> points = points(pointPayload);
        Map<String, JsonNode> items = new HashMap<>();
        for (JsonNode item : coveragePayload.path("items")) {
            String id = text(item, "pointId");
            if (selectedPointIds == null || selectedPointIds.contains(id)) items.put(id, item);
        }
        if (selectedPointIds != null && !items.keySet().equals(selectedPointIds)) {
            throw new BusinessException("本批测试点必须全部来自当前覆盖计划");
        }
        Map<String, Set<String>> methodsByPoint = new HashMap<>();
        Map<String, Set<String>> coverageByPoint = new HashMap<>();
        Map<String, Set<String>> scenariosByPoint = new HashMap<>();
        for (JsonNode item : payload.path("cases")) {
            requiredText(item, "caseTitle");
            String module = requiredText(item, "moduleName");
            if (!MODULE_PATH.matcher(module).matches()) {
                throw new BusinessException("所属模块须写成 /页面/功能 的层级路径");
            }
            String steps = requiredText(item, "steps");
            if (text(item, "precondition").contains("需求与澄清已确认")
                    || text(item, "precondition").equals("测试环境可访问")) {
                throw new BusinessException("前置条件应写实际账号、开关或数据；无特殊条件请留空");
            }
            String expected = requiredText(item, "expectedResult");
            List<String> actions = numberedActions(steps);
            List<String> results = numberedActions(expected);
            if (actions.isEmpty() || actions.size() != results.size()) {
                throw new BusinessException("用例至少需要一个具体步骤，且每步预期必须一一对应");
            }
            for (int index = 0; index < actions.size(); index++) {
                if (actions.get(index).codePointCount(0, actions.get(index).length()) < 4
                        || results.get(index).codePointCount(0, results.get(index).length()) < 4
                        || GENERIC_EXPECTATIONS.contains(results.get(index).replaceAll("[。！!，,\\s]", ""))) {
                    throw new BusinessException("用例步骤须写明对象、动作和数据，预期须写明可观察的具体结果");
                }
            }
            String visible = text(item, "precondition") + "\n" + steps + "\n" + expected;
            if (INTERNAL_REF.matcher(visible).find()) throw new BusinessException("用例正文不能出现内部测试点编号");
            if (PRIOR_CASE_DEPENDENCY.matcher(visible).find())
                throw new BusinessException("每条用例必须独立执行；请将所需状态写成可独立准备的前置条件，或在本条步骤中完成准备，不得依赖前一条用例");
            String scenario = requiredText(item, "scenarioType");
            if (!SCENARIOS.contains(scenario)) throw new BusinessException("用例场景类型无效");
            String strategy = requiredText(item, "caseStrategy");
            if (!Set.of("NODE_FOCUSED", "FLOW_COMPOSED").contains(strategy)) {
                throw new BusinessException("用例策略必须是节点验证或完整流程");
            }
            Set<String> pointRefs = strings(item, "sourceTestPointRefs");
            Set<String> methods = strings(item, "designMethods");
            Set<String> coverage = strings(item, "designCoverage");
            strings(item, "sourceBasis");
            if (!item.path("unsupportedItems").isArray()) throw new BusinessException("用例必须声明未证实的假设");
            JsonNode confidence = item.path("confidence");
            if (!confidence.isNumber() || confidence.asDouble() < 0 || confidence.asDouble() > 1
                    || (!item.path("unsupportedItems").isEmpty() && confidence.asDouble() > 0.65)) {
                throw new BusinessException("用例置信度与未证实假设不一致");
            }
            for (String id : pointRefs) {
                if (!items.containsKey(id)) throw new BusinessException("用例引用了未纳入生成范围的测试点：" + id);
                methodsByPoint.computeIfAbsent(id, ignored -> new HashSet<>()).addAll(methods);
                coverageByPoint.computeIfAbsent(id, ignored -> new HashSet<>()).addAll(coverage);
                scenariosByPoint.computeIfAbsent(id, ignored -> new HashSet<>()).add(scenario);
            }
        }
        for (var entry : items.entrySet()) {
            String id = entry.getKey();
            JsonNode plan = entry.getValue();
            requireAll(methodsByPoint.getOrDefault(id, Set.of()), new ArrayList<>(strings(plan, "designMethods")),
                    "测试点 " + id + " 的用例未兑现全部设计方法");
            Set<String> requiredCoverage = strings(plan, "coverageRequirements");
            requireAll(coverageByPoint.getOrDefault(id, Set.of()), new ArrayList<>(requiredCoverage),
                    "测试点 " + id + " 的用例未兑现全部覆盖义务");
            String requiredScenario = FunctionalTestDesignPolicy.scenarioType(text(points.get(id), "pointType"));
            if (!"COMBINATION".equals(requiredScenario)
                    && !scenariosByPoint.getOrDefault(id, Set.of()).contains(requiredScenario)) {
                throw new BusinessException("测试点 " + id + " 缺少对应场景类型 " + requiredScenario);
            }
        }
    }

    static boolean matchesDraft(JsonNode casePayload, String title, String module, String precondition,
                                String steps, String expected) {
        return findDraft(casePayload, title, module, precondition, steps, expected) != null;
    }

    static JsonNode findDraft(JsonNode casePayload, String title, String module, String precondition,
                              String steps, String expected) {
        for (JsonNode item : casePayload.path("cases")) {
            if (text(item, "caseTitle").equals(title.strip())
                    && text(item, "moduleName").equals(module == null ? "" : module.strip())
                    && text(item, "precondition").equals(precondition == null ? "" : precondition.strip())
                    && text(item, "steps").equals(steps.strip())
                    && text(item, "expectedResult").equals(expected.strip())) return item;
        }
        return null;
    }

    private static Map<String, JsonNode> points(JsonNode payload) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode point : payload.path("points")) {
            String id = requiredText(point, "id");
            if (!id.matches("TP[1-9][0-9]*") || result.putIfAbsent(id, point) != null) {
                throw new BusinessException("测试点编号必须唯一且形如 TP1");
            }
            requiredText(point, "title");
            String type = requiredText(point, "pointType");
            if (!POINT_TYPES.contains(type)) throw new BusinessException("测试点类型无效：" + type);
            String scope = requiredText(point, "scopeDecision");
            if (!SCOPES.contains(scope)) throw new BusinessException("测试点范围决定无效");
            strings(point, "sourceBasis");
            if (!"GENERATE".equals(scope)) requiredText(point, "scopeReason");
        }
        if (result.isEmpty()) throw new BusinessException("测试点不能为空");
        return result;
    }

    private static void requireAll(Set<String> actual, List<String> required, String message) {
        if (!actual.containsAll(required)) throw new BusinessException(message + "：" + required.stream()
                .filter(item -> !actual.contains(item)).toList());
    }

    private static Set<String> strings(JsonNode node, String key) {
        JsonNode array = node.path(key);
        if (!array.isArray() || array.isEmpty()) throw new BusinessException(key + " 必须是非空数组");
        Set<String> result = new HashSet<>();
        for (JsonNode item : array) {
            if (!item.isTextual() || item.asText().isBlank()) throw new BusinessException(key + " 包含空值");
            result.add(item.asText());
        }
        return result;
    }

    private static String requiredText(JsonNode node, String key) {
        String value = text(node, key);
        if (value.isBlank()) throw new BusinessException(key + " 不能为空");
        return value;
    }

    private static String text(JsonNode node, String key) {
        return node == null ? "" : node.path(key).asText("").strip();
    }

    private static List<String> numberedActions(String value) {
        List<String> lines = new ArrayList<>();
        for (String line : value.split("(?i)<br\\s*/?>|\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            var matcher = NUMBERED_ACTION.matcher(trimmed);
            if (!matcher.matches() || !trimmed.matches("^" + (lines.size() + 1) + "[.、)].*")) {
                throw new BusinessException("用例步骤与预期须从 1 开始连续编号，每行一项");
            }
            lines.add(trimmed.replaceFirst("^\\d+[.、)]\\s*", "").strip());
        }
        return lines;
    }
}

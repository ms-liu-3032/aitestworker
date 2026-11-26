package com.company.aitest.dsh;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import com.company.aitest.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/** Shared versioned rules for UI/API. Never persist provider hosts, credentials or raw log lines here. */
final class LogAssertionPolicy {
    private LogAssertionPolicy() {}
    static void validateMapping(JsonNode mapping, boolean api) {
        JsonNode rules = mapping.path("logAssertions");
        if (rules.isMissingNode()) return;
        if (!rules.isArray() || rules.size() > 10) fail("日志断言须为至多 10 项的数组");
        var ids = new HashSet<String>();
        for (JsonNode rule : rules) {
            fields(rule, Set.of("id", "stepIndex", "serviceName", "purpose", "expected", "timeoutMs", "correlation"));
            String id = rule.path("id").asText();
            int index = rule.path("stepIndex").asInt(-1);
            if (!id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || !ids.add(id)
                    || !rule.path("stepIndex").isIntegralNumber() || !rule.path("stepIndex").canConvertToInt() || index < 0 || index >= mapping.path("steps").size()) fail("日志规则 ID 或步骤序号无效");
            if (!Set.of("REQUEST_ASSOCIATION", "BUSINESS_EVENT").contains(rule.path("purpose").asText())) fail("日志规则须声明关联证据或业务事件目的");
            JsonNode expected = rule.path("expected");
            if (!expected.isTextual() || expected.asText().isBlank() || expected.asText().length() > 512
                    || expected.asText().contains("\n") || expected.asText().contains("\r")
                    || expected.asText().matches("(?is).*(password|passwd|authorization|cookie|token|secret)\\s*[=:].*")) fail("日志预期须为不含凭据的非空单行文本");
            if (rule.has("serviceName") && (!rule.path("serviceName").isTextual()
                    || !rule.path("serviceName").asText().matches("[A-Za-z0-9_-]{1,128}"))) fail("日志服务名称无效");
            if (rule.has("timeoutMs") && (!rule.path("timeoutMs").isIntegralNumber() || !rule.path("timeoutMs").canConvertToInt()
                    || rule.path("timeoutMs").asInt(-1) < 0 || rule.path("timeoutMs").asInt() > 60000)) fail("日志等待须为 0-60000 毫秒");
            if (!api && !mapping.path("steps").get(index).path("waitForResponse").isObject()) fail("UI 日志断言步骤须等待已核实的业务响应");
            if (rule.has("correlation")) {
                JsonNode correlation = rule.path("correlation");
                fields(correlation, Set.of("target", "path"));
                String path = correlation.path("path").asText();
                if (!("JSON".equals(correlation.path("target").asText()) && path.length() <= 256
                        && path.matches("\\$(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[[0-9]{1,4}\\])*")
                        || "HEADER".equals(correlation.path("target").asText()) && Set.of("x-trace-id", "x-request-id").contains(path.toLowerCase())))
                    fail("日志关联仅支持简单响应 JSON 路径或请求追踪响应头");
            }
        }
    }

    static JsonNode evidence(ObjectMapper mapper, JsonNode mapping, String runResult, JsonNode evidence) {
        JsonNode rules = mapping.path("logAssertions");
        if (evidence == null || evidence.isNull()) {
            if ("PASS".equals(runResult) && !rules.isEmpty()) fail("PASS 缺少必需日志断言证据");
            return null;
        }
        if (!evidence.isArray() || evidence.size() > rules.size()) fail("日志证据格式或覆盖范围无效");
        var byId = new HashMap<String, JsonNode>();
        for (JsonNode rule : rules) byId.put(rule.path("id").asText(), rule);
        var seen = new HashSet<String>();
        ArrayNode clean = mapper.createArrayNode();
        for (JsonNode item : evidence) {
            fields(item, Set.of("ruleId", "stepIndex", "purpose", "expected", "serviceName", "result", "reason", "correlationSha256", "instances"));
            JsonNode rule = byId.get(item.path("ruleId").asText());
            if (rule == null || !seen.add(item.path("ruleId").asText()) || !item.path("stepIndex").equals(rule.path("stepIndex"))
                    || !item.path("expected").equals(rule.path("expected")) || !item.path("purpose").equals(rule.path("purpose"))) fail("日志证据与冻结规则不一致");
            String result = item.path("result").asText();
            if (!Set.of("PASS", "FAIL", "INCONCLUSIVE").contains(result)
                    || "PASS".equals(runResult) && !"PASS".equals(result)) fail("PASS 包含未完成的日志断言");
            if (!item.path("reason").asText().matches("[A-Z0-9_]{1,100}")) fail("日志证据原因无效");
            if (item.has("serviceName") && !item.path("serviceName").asText().matches("[A-Za-z0-9_-]{1,128}")) fail("日志证据服务无效");
            if (rule.has("serviceName") && item.has("serviceName")
                    && !rule.path("serviceName").asText().replace('_', '-').equalsIgnoreCase(item.path("serviceName").asText().replace('_', '-'))) fail("日志证据服务与规则不一致");
            JsonNode instances = item.path("instances");
            if (!instances.isArray() || instances.size() > 8) fail("日志实例证据无效");
            boolean matched = false;
            var instanceIds = new HashSet<String>();
            for (JsonNode proof : instances) {
                fields(proof, Set.of("instanceId", "result", "reason", "startedAt", "completedAt", "checkedBytes", "matchedLineSha256"));
                String instanceId = proof.path("instanceId").asText();
                if (!instanceId.matches("[A-Za-z0-9_-]{1,128}") || !instanceIds.add(instanceId)
                        || !Set.of("PASS", "FAIL", "INCONCLUSIVE").contains(proof.path("result").asText())
                        || !proof.path("reason").asText().matches("[A-Z0-9_]{1,100}")
                        || !proof.path("checkedBytes").isIntegralNumber() || !proof.path("checkedBytes").canConvertToInt() || proof.path("checkedBytes").asInt(-1) < 0
                        || proof.path("checkedBytes").asInt() > 262144) fail("日志实例证据不完整");
                try {
                    if (Instant.parse(proof.path("startedAt").asText()).isAfter(Instant.parse(proof.path("completedAt").asText()))) fail("日志时间范围无效");
                } catch (java.time.format.DateTimeParseException e) { fail("日志时间格式无效"); }
                if ("PASS".equals(proof.path("result").asText())) {
                    if (!proof.path("matchedLineSha256").asText().matches("[a-f0-9]{64}") || proof.path("checkedBytes").asInt() == 0) fail("匹配日志缺少新增内容哈希");
                    matched = true;
                }
            }
            if ("PASS".equals(result) && (!matched || !item.path("correlationSha256").asText().matches("[a-f0-9]{64}")
                    || !item.has("serviceName"))) fail("通过日志证据缺少实例或请求关联");
            clean.add(item.deepCopy());
        }
        if ("PASS".equals(runResult) && seen.size() != rules.size()) fail("PASS 日志证据未覆盖全部冻结规则");
        if (clean.toString().length() > 60000) fail("日志证据过大");
        return clean;
    }
    private static void fields(JsonNode value, Set<String> allowed) {
        if (!value.isObject()) fail("日志配置须为对象");
        value.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) fail("日志配置含不支持字段：" + name); });
    }
    private static void fail(String message) { throw new BusinessException(message); }
}

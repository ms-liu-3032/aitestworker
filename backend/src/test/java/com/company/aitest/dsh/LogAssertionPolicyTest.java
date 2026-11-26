package com.company.aitest.dsh;

import com.company.aitest.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogAssertionPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode mapping() throws Exception {
        return mapper.readTree("""
          {"steps":[{"path":"/vsk/vst-visitor/api/invitations"}],"logAssertions":[
            {"id":"invite","stepIndex":0,"purpose":"REQUEST_ASSOCIATION","expected":"POST /api/invitations"}]}
          """);
    }
    private ArrayNode evidence() throws Exception {
        return (ArrayNode) mapper.readTree("""
          [{"ruleId":"invite","stepIndex":0,"purpose":"REQUEST_ASSOCIATION","expected":"POST /api/invitations",
            "serviceName":"VST-VISITOR","result":"PASS","reason":"CORRELATED_NEW_LOG_MATCH",
            "correlationSha256":"%s","instances":[{"instanceId":"new-id","result":"PASS","reason":"CORRELATED_NEW_LOG_MATCH",
            "startedAt":"2026-11-19T07:00:00Z","completedAt":"2026-11-19T07:00:01Z","checkedBytes":100,
            "matchedLineSha256":"%s"}]}]
          """.formatted("a".repeat(64), "b".repeat(64)));
    }
    @Test void acceptsOptionalRulesAndEvidenceForOldMappings() throws Exception {
        JsonNode old = mapper.readTree("{\"steps\":[{}]}");
        LogAssertionPolicy.validateMapping(old, true);
        assertNull(LogAssertionPolicy.evidence(mapper, old, "PASS", null));
        LogAssertionPolicy.validateMapping(mapping(), true);
        assertEquals(evidence(), LogAssertionPolicy.evidence(mapper, mapping(), "PASS", evidence()));
    }
    @Test void requiresFullFrozenLogCoverageForPass() throws Exception {
        assertThrows(BusinessException.class, () -> LogAssertionPolicy.evidence(mapper, mapping(), "PASS", null));
        assertThrows(BusinessException.class, () -> LogAssertionPolicy.evidence(mapper, mapping(), "PASS", mapper.createArrayNode()));
        var changed = evidence(); ((ObjectNode) changed.get(0)).put("expected", "anything");
        assertThrows(BusinessException.class, () -> LogAssertionPolicy.evidence(mapper, mapping(), "PASS", changed));
        var duplicate = evidence(); duplicate.add(duplicate.get(0).deepCopy());
        assertThrows(BusinessException.class, () -> LogAssertionPolicy.evidence(mapper, mapping(), "PASS", duplicate));
    }
    @Test void inconclusiveCannotVerifyAssetButMayBeStoredAsFailedRun() throws Exception {
        var unavailable = evidence();
        ((ObjectNode) unavailable.get(0)).put("result", "INCONCLUSIVE").put("reason", "LOG_INSTANCE_SET_CHANGED").set("instances", mapper.createArrayNode());
        assertThrows(BusinessException.class, () -> LogAssertionPolicy.evidence(mapper, mapping(), "PASS", unavailable));
        assertEquals(unavailable, LogAssertionPolicy.evidence(mapper, mapping(), "ENVIRONMENT_FAILURE", unavailable));
    }
    @Test void rejectsSecretsHostsAndUnboundedRules() throws Exception {
        for (String field : new String[]{"domain", "password", "cookie", "providerUrl", "instanceId"}) {
            var value = mapping(); ((ObjectNode) value.path("logAssertions").get(0)).put(field, "forbidden");
            assertThrows(BusinessException.class, () -> LogAssertionPolicy.validateMapping(value, true));
        }
        var value = mapping(); ((ObjectNode) value.path("logAssertions").get(0)).put("timeoutMs", 60001);
        assertThrows(BusinessException.class, () -> LogAssertionPolicy.validateMapping(value, true));
        assertThrows(BusinessException.class, () -> LogAssertionPolicy.validateMapping(mapping(), false));
        var fractional = mapping(); ((ObjectNode) fractional.path("logAssertions").get(0)).put("timeoutMs", 0.5);
        assertThrows(BusinessException.class, () -> LogAssertionPolicy.validateMapping(fractional, true));
    }
    @Test void matchingProofNeedsNewBytesHashAndOrderedWindow() throws Exception {
        var noHash = evidence(); ((ObjectNode) noHash.get(0).path("instances").get(0)).remove("matchedLineSha256");
        assertThrows(BusinessException.class, () -> LogAssertionPolicy.evidence(mapper, mapping(), "PASS", noHash));
        var empty = evidence(); ((ObjectNode) empty.get(0).path("instances").get(0)).put("checkedBytes", 0);
        assertThrows(BusinessException.class, () -> LogAssertionPolicy.evidence(mapper, mapping(), "PASS", empty));
        var time = evidence(); ((ObjectNode) time.get(0).path("instances").get(0)).put("completedAt", "2026-11-18T00:00:00Z");
        assertThrows(BusinessException.class, () -> LogAssertionPolicy.evidence(mapper, mapping(), "PASS", time));
        var raw = evidence(); ((ObjectNode) raw.get(0)).put("matchedLine", "secret");
        assertThrows(BusinessException.class, () -> LogAssertionPolicy.evidence(mapper, mapping(), "PASS", raw));
    }
}

package com.company.aitest.dsh;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UiCoverageServiceTest {
    private static UiCoverageService.TaskRule rule(String code, String applicability, String status) {
        return new UiCoverageService.TaskRule(code, 1, "RUN_ASSERTION", applicability, "已核对页面", status);
    }
    private static UiCoverageService.Obligation item(String code, String status) {
        return new UiCoverageService.Obligation(1L, code, "scene-1", "/page", "场景",
                "需求", "REQUIRED", status.equals("COVERED") ? 10L : null, null, status);
    }

    @Test
    void happyPathCannotHideAnotherApplicableRule() {
        var covered = rule("UI_EXPECTED_BEHAVIOR", "APPLICABLE", "COVERED");
        var missing = rule("UI_REQUIRED_FIELD", "APPLICABLE", "PENDING");
        assertEquals("INVENTORY_UNCONFIRMED", UiCoverageService.assess("UNCONFIRMED",
                List.of(covered), List.of(item("UI_EXPECTED_BEHAVIOR", "COVERED"))));
        assertEquals("INCOMPLETE", UiCoverageService.assess("CONFIRMED",
                List.of(covered, missing), List.of(item("UI_EXPECTED_BEHAVIOR", "COVERED"))));
        assertEquals("INCOMPLETE", UiCoverageService.assess("CONFIRMED",
                List.of(covered, rule("UI_REQUIRED_FIELD", "UNKNOWN", "UNKNOWN")),
                List.of(item("UI_EXPECTED_BEHAVIOR", "COVERED"))));
        assertEquals("PASS", UiCoverageService.assess("CONFIRMED",
                List.of(covered, rule("UI_REQUIRED_FIELD", "NOT_APPLICABLE", "NOT_APPLICABLE")),
                List.of(item("UI_EXPECTED_BEHAVIOR", "COVERED"))));
    }

    @Test
    void incompleteOrMissingEvidenceNeverPasses() {
        var covered = rule("UI_EXPECTED_BEHAVIOR", "APPLICABLE", "COVERED");
        assertEquals("INCOMPLETE", UiCoverageService.assess("CONFIRMED", List.of(covered), List.of()));
        assertEquals("INCOMPLETE", UiCoverageService.assess("CONFIRMED", List.of(covered),
                List.of(item("UI_EXPECTED_BEHAVIOR", "PENDING"))));
    }
}

package com.company.aitest.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FunctionalTestDesignPolicyTest {
    @Test
    void boundaryRequiresValidInvalidAndNearBoundaryCoverage() {
        assertEquals("BOUNDARY", FunctionalTestDesignPolicy.scenarioType("BOUNDARY"));
        assertEquals(java.util.List.of("等价类划分法", "边界值分析法"),
                FunctionalTestDesignPolicy.designMethods("BOUNDARY", "边界值"));
        assertTrue(FunctionalTestDesignPolicy.coverageRequirements("BOUNDARY")
                .containsAll(java.util.List.of("VALID_EQUIVALENCE_CLASS", "INVALID_EQUIVALENCE_CLASS",
                        "AT_BOUNDARY", "JUST_INSIDE_BOUNDARY", "JUST_OUTSIDE_BOUNDARY")));
    }

    @Test
    void functionalDimensionsAreNotAllPositiveScenario() {
        assertEquals("NEGATIVE", FunctionalTestDesignPolicy.scenarioType("EXCEPTION"));
        assertEquals("COMBINATION", FunctionalTestDesignPolicy.scenarioType("AUTH"));
        assertEquals("STATE", FunctionalTestDesignPolicy.scenarioType("IDEMPOTENT"));
        assertTrue(FunctionalTestDesignPolicy.designMethods("CONCURRENCY", "")
                .contains("正交实验设计法"));
    }
}

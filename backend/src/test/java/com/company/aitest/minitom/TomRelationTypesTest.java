package com.company.aitest.minitom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TomRelationTypesTest {
    @Test
    void exposesDirectedTypesForFlowAndState() {
        assertEquals("MODULE:PAGE", TomRelationTypes.expectedPair("MODULE_HAS_PAGE"));
        assertEquals("FLOW:STATE", TomRelationTypes.expectedPair("FLOW_CHANGES_STATE"));
        assertEquals("STATE:STATE", TomRelationTypes.expectedPair("STATE_TRANSITIONS_TO"));
        assertNull(TomRelationTypes.expectedPair(null));
        assertNull(TomRelationTypes.expectedPair("CONTAINS"));
        assertEquals(10, TomRelationTypes.definitions().size());
    }
}

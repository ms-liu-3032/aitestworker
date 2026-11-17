package com.company.aitest.minitom;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared TOM edge vocabulary for Web and bound DSH proposals. */
public final class TomRelationTypes {
    private static final Map<String, String> PAIRS;

    static {
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put("MODULE_HAS_PAGE", "MODULE:PAGE");
        pairs.put("PAGE_HAS_FIELD", "PAGE:FIELD");
        pairs.put("ROLE_CAN_ACTION", "ROLE:ACTION");
        pairs.put("FLOW_HAS_STEP", "FLOW:ACTION");
        pairs.put("ACTION_CHANGES_STATE", "ACTION:STATE");
        pairs.put("ACTION_HAS_ASSERTION", "ACTION:ASSERTION");
        pairs.put("MODULE_HAS_ACTION", "MODULE:ACTION");
        pairs.put("ACTION_HAS_FLOW", "ACTION:FLOW");
        pairs.put("FLOW_CHANGES_STATE", "FLOW:STATE");
        pairs.put("STATE_TRANSITIONS_TO", "STATE:STATE");
        PAIRS = Map.copyOf(pairs);
    }

    private TomRelationTypes() {}

    public static String expectedPair(String relationType) {
        return relationType == null ? null : PAIRS.get(relationType);
    }

    public static List<Map<String, String>> definitions() {
        return List.of(
                entry("MODULE_HAS_PAGE", "MODULE", "PAGE"),
                entry("PAGE_HAS_FIELD", "PAGE", "FIELD"),
                entry("ROLE_CAN_ACTION", "ROLE", "ACTION"),
                entry("FLOW_HAS_STEP", "FLOW", "ACTION"),
                entry("ACTION_CHANGES_STATE", "ACTION", "STATE"),
                entry("ACTION_HAS_ASSERTION", "ACTION", "ASSERTION"),
                entry("MODULE_HAS_ACTION", "MODULE", "ACTION"),
                entry("ACTION_HAS_FLOW", "ACTION", "FLOW"),
                entry("FLOW_CHANGES_STATE", "FLOW", "STATE"),
                entry("STATE_TRANSITIONS_TO", "STATE", "STATE"));
    }

    private static Map<String, String> entry(String type, String from, String to) {
        return Map.of("type", type, "fromType", from, "toType", to);
    }
}

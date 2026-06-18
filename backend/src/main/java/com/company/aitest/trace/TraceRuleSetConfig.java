package com.company.aitest.trace;

import java.util.List;

record TraceRuleSetConfig(
        String name,
        List<WorkflowRule> workflows,
        List<String> objectHistoryContextContains,
        List<String> objectSelectionDialogContains,
        List<String> objectSelectionTargetContains,
        List<String> dialogContextContains,
        List<DialogInputConfirmFlowRule> dialogInputConfirmFlows,
        List<EntryNavigationSubmitFlowRule> entryNavigationSubmitFlows,
        List<DescribeRule> inputRules,
        List<DescribeRule> changeRules,
        List<DescribeRule> clickRules,
        List<ObjectLabelRule> objectLabelRules,
        List<CheckboxSemanticRule> checkboxSemanticRules,
        List<DialogActionRule> dialogActionRules,
        List<BusinessActionRule> businessActionRules
) {
    static TraceRuleSetConfig empty() {
        return new TraceRuleSetConfig(null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    record WorkflowRule(String kind, String ref) {
    }

    record DescribeRule(
            String targetEquals,
            String targetContains,
            String valueEquals,
            String pageNameContains,
            String dialogTitleContains,
            String sectionTitleContains,
            Boolean targetEqualsValue,
            Boolean dialogContextRequired,
            Boolean objectSelectionContext,
            Boolean selectedObjectRequired,
            String resultType,
            String template
    ) {
    }

    record ObjectLabelRule(String pageTitleContains, String label) {
    }

    record CheckboxSemanticRule(String elementTextContains, String semantic) {
    }

    record DialogActionRule(String elementTextContains, String dialogTitleContains, String action) {
    }

    record BusinessActionRule(String contextContains, String exactText, String containsText, String action) {
    }

    record DialogInputConfirmFlowRule(
            String name,
            String startDescription,
            String activePageContains,
            String sourcePageContains,
            List<String> sourceDescriptionContainsAny,
            Integer sourceLookaheadDistance,
            List<String> entryExcludeContains,
            String inputPrefix,
            String mergedInputTemplate,
            String fallbackValue,
            List<String> placeholderTokens,
            List<String> sendSmsDescriptions,
            List<String> explicitNoSendDescriptions,
            List<String> cancelDescriptions,
            String cancelRewrite,
            List<String> confirmDescriptions,
            String confirmRewrite,
            String implicitConfirmRewrite,
            List<String> successPrefixes,
            List<String> successDescriptions,
            List<String> dialogNoiseContains,
            List<String> dialogNoiseRegexes,
            List<String> detailProbeDescriptions,
            List<String> focusClickDescriptions,
            List<String> typingNoiseContains,
            List<String> confirmSuffixStripTokens
    ) {
    }

    record EntryNavigationSubmitFlowRule(
            String name,
            String sourcePageContains,
            String flowPageContains,
            String entryDescriptionContains,
            Integer entryLookaheadDistance,
            String entryNavigationDescriptionContains,
            String entryRewrite,
            String navigationRewrite,
            List<String> fieldFocusNoisePrefixes,
            List<String> fieldFocusFollowupPrefixes,
            List<String> fieldFocusTerminalDescriptions,
            String intermediateConfirmPageContains,
            List<String> intermediateConfirmDescriptions,
            String intermediateConfirmRewrite,
            List<String> submitDescriptions,
            String submitRewrite,
            Integer submitLookaheadDistance,
            List<String> recentSubmitDescriptions,
            List<String> submitApiContains,
            List<String> notificationChoiceDescriptions,
            List<String> notificationChoiceRewrites,
            List<String> languageChoiceDescriptions,
            List<String> languageChoiceRewrites,
            List<String> timeNoiseRegexes,
            List<String> successDescriptions
    ) {
    }

    DialogInputConfirmFlowRule resolveDialogInputConfirmFlow(String ref) {
        List<DialogInputConfirmFlowRule> candidates = dialogInputConfirmFlows == null ? List.of() : dialogInputConfirmFlows;
        if (candidates.isEmpty()) {
            return null;
        }
        if (ref == null || ref.isBlank()) {
            return candidates.get(0);
        }
        for (DialogInputConfirmFlowRule rule : candidates) {
            String configuredName = rule.name();
            if (configuredName == null || configuredName.isBlank()) {
                continue;
            }
            if (ref.equals(configuredName)) {
                return rule;
            }
        }
        return null;
    }

    EntryNavigationSubmitFlowRule resolveEntryNavigationSubmitFlow(String ref) {
        List<EntryNavigationSubmitFlowRule> candidates = entryNavigationSubmitFlows == null ? List.of() : entryNavigationSubmitFlows;
        if (candidates.isEmpty()) {
            return null;
        }
        if (ref == null || ref.isBlank()) {
            return candidates.get(0);
        }
        for (EntryNavigationSubmitFlowRule rule : candidates) {
            String configuredName = rule.name();
            if (configuredName == null || configuredName.isBlank()) {
                continue;
            }
            if (ref.equals(configuredName)) {
                return rule;
            }
        }
        return null;
    }
}

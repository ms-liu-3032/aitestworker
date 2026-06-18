package com.company.aitest.trace;

import java.util.List;

final class EntryNavigationSubmitWorkflowProcessor implements TraceWorkflowProcessor {

    private static final String FLAG_TRIGGER_PENDING = "triggerPending";
    private static final String FLAG_IN_FLOW = "inFlow";
    private static final TraceWorkflowTransition.Condition<TraceRuleSetConfig.EntryNavigationSubmitFlowRule> ENTRY_CANDIDATE_CONDITION =
            TraceWorkflowConditions.all(
                    TraceWorkflowConditions.actionType("CLICK"),
                    TraceWorkflowConditions.pageContains(TraceRuleSetConfig.EntryNavigationSubmitFlowRule::sourcePageContains),
                    TraceWorkflowConditions.descriptionContains(TraceRuleSetConfig.EntryNavigationSubmitFlowRule::entryDescriptionContains),
                    TraceWorkflowConditions.hasAheadNavigationDescriptionContains(
                            1,
                            flowRule -> TraceWorkflowSupport.defaultInt(flowRule.entryLookaheadDistance(), 3),
                            TraceRuleSetConfig.EntryNavigationSubmitFlowRule::entryNavigationDescriptionContains
                    )
            );
    private static final List<TraceWorkflowTransition<TraceRuleSetConfig.EntryNavigationSubmitFlowRule>> TRANSITIONS = List.of(
            TraceWorkflowTransition.<TraceRuleSetConfig.EntryNavigationSubmitFlowRule>of(
                    "entryCandidate",
                    ENTRY_CANDIDATE_CONDITION,
                    (runtime, step, rule, desc, pageName) -> {
                        TraceStepNormalizer.CleanTraceStep rewritten = new TraceStepNormalizer.CleanTraceStep(
                                0, step.actor(), step.actionType(), TraceWorkflowSupport.defaultText(rule.entryRewrite(), desc),
                                step.pageName(), step.pageUrl(), step.relativeMs()
                        );
                        if (!runtime.hasLastResult() || !TraceWorkflowSupport.sameStepMeaning(runtime.lastResult(), rewritten)) {
                            runtime.addStep(rewritten);
                        }
                        return TraceWorkflowOutcome.none();
                    }
            ).setFlagsAfter(FLAG_TRIGGER_PENDING, FLAG_IN_FLOW).advanceAfter(),
            TraceWorkflowTransition.<TraceRuleSetConfig.EntryNavigationSubmitFlowRule>of(
                    "navigation",
                    TraceWorkflowConditions.all(
                            TraceWorkflowConditions.actionType("NAVIGATION"),
                            TraceWorkflowConditions.descriptionStartsWithLiteral("跳转到页面“"),
                            TraceWorkflowConditions.descriptionContains(TraceRuleSetConfig.EntryNavigationSubmitFlowRule::entryNavigationDescriptionContains),
                            TraceWorkflowConditions.any(
                                    TraceWorkflowConditions.hasFlag(FLAG_TRIGGER_PENDING),
                                    TraceWorkflowConditions.hasFlag(FLAG_IN_FLOW)
                            )
                    ),
                    (runtime, step, rule, desc, pageName) -> {
                        runtime.addRewriteFromCurrent(TraceWorkflowSupport.defaultText(rule.navigationRewrite(), desc));
                        return TraceWorkflowOutcome.none();
                    }
            ).clearFlagsAfter(FLAG_TRIGGER_PENDING).setFlagsAfter(FLAG_IN_FLOW).advanceAfter(),
            TraceWorkflowTransition.<TraceRuleSetConfig.EntryNavigationSubmitFlowRule>of(
                    "timeNoise",
                    TraceWorkflowConditions.all(
                            TraceWorkflowConditions.hasFlag(FLAG_IN_FLOW),
                            TraceWorkflowConditions.actionType("CLICK"),
                            TraceWorkflowConditions.lastResultDescriptionContainsAny(TraceRuleSetConfig.EntryNavigationSubmitFlowRule::successDescriptions),
                            TraceWorkflowConditions.descriptionMatchesAnyRegex(TraceRuleSetConfig.EntryNavigationSubmitFlowRule::timeNoiseRegexes)
                    ),
                    (runtime, step, rule, desc, pageName) -> TraceWorkflowOutcome.none()
            ).advanceAfter(),
            TraceWorkflowTransition.<TraceRuleSetConfig.EntryNavigationSubmitFlowRule>of(
                    "fieldFocusNoise",
                    TraceWorkflowConditions.all(
                            TraceWorkflowConditions.hasFlag(FLAG_IN_FLOW),
                            TraceWorkflowConditions.actionType("CLICK"),
                            TraceWorkflowConditions.pageContains(TraceRuleSetConfig.EntryNavigationSubmitFlowRule::flowPageContains),
                            TraceWorkflowConditions.descriptionStartsWithAny(TraceRuleSetConfig.EntryNavigationSubmitFlowRule::fieldFocusNoisePrefixes),
                            TraceWorkflowConditions.any(
                                    TraceWorkflowConditions.hasAheadDescriptionStartsWithAny(
                                            1,
                                            flowRule -> 3,
                                            TraceRuleSetConfig.EntryNavigationSubmitFlowRule::fieldFocusFollowupPrefixes
                                    ),
                                    TraceWorkflowConditions.hasAheadDescriptionEqualsAny(
                                            1,
                                            flowRule -> 3,
                                            TraceRuleSetConfig.EntryNavigationSubmitFlowRule::fieldFocusTerminalDescriptions
                                    )
                            )
                    ),
                    (runtime, step, rule, desc, pageName) -> TraceWorkflowOutcome.none()
            ).advanceAfter(),
            TraceWorkflowTransition.<TraceRuleSetConfig.EntryNavigationSubmitFlowRule>of(
                    "intermediateConfirm",
                    TraceWorkflowConditions.all(
                            TraceWorkflowConditions.hasFlag(FLAG_IN_FLOW),
                            TraceWorkflowConditions.actionType("CLICK"),
                            TraceWorkflowConditions.pageContains(TraceRuleSetConfig.EntryNavigationSubmitFlowRule::intermediateConfirmPageContains),
                            TraceWorkflowConditions.descriptionEqualsAny(TraceRuleSetConfig.EntryNavigationSubmitFlowRule::intermediateConfirmDescriptions)
                    ),
                    (runtime, step, rule, desc, pageName) -> {
                        TraceStepNormalizer.CleanTraceStep rewritten = new TraceStepNormalizer.CleanTraceStep(
                                0, step.actor(), step.actionType(), TraceWorkflowSupport.defaultText(rule.intermediateConfirmRewrite(), desc),
                                step.pageName(), step.pageUrl(), step.relativeMs()
                        );
                        if (!runtime.hasLastResult() || !TraceWorkflowSupport.sameStepMeaning(runtime.lastResult(), rewritten)) {
                            runtime.addStep(rewritten);
                        }
                        return TraceWorkflowOutcome.none();
                    }
            ).advanceAfter(),
            TraceWorkflowTransition.<TraceRuleSetConfig.EntryNavigationSubmitFlowRule>of(
                    "submit",
                    TraceWorkflowConditions.all(
                            TraceWorkflowConditions.hasFlag(FLAG_IN_FLOW),
                            TraceWorkflowConditions.actionType("CLICK"),
                            TraceWorkflowConditions.descriptionContainsAny(TraceRuleSetConfig.EntryNavigationSubmitFlowRule::submitDescriptions),
                            TraceWorkflowConditions.any(
                                    TraceWorkflowConditions.hasAheadDescriptionContainsAny(
                                            1,
                                            flowRule -> TraceWorkflowSupport.defaultInt(flowRule.submitLookaheadDistance(), 4),
                                            TraceRuleSetConfig.EntryNavigationSubmitFlowRule::notificationChoiceDescriptions
                                    ),
                                    TraceWorkflowConditions.descriptionContainsAny(TraceRuleSetConfig.EntryNavigationSubmitFlowRule::submitApiContains)
                            )
                    ),
                    (runtime, step, rule, desc, pageName) -> {
                        TraceStepNormalizer.CleanTraceStep rewritten = new TraceStepNormalizer.CleanTraceStep(
                                0, step.actor(), step.actionType(), TraceWorkflowSupport.defaultText(rule.submitRewrite(), desc),
                                step.pageName(), step.pageUrl(), step.relativeMs()
                        );
                        if (!TraceWorkflowConditions.recentResultDescriptionContainsAny(
                                2,
                                TraceRuleSetConfig.EntryNavigationSubmitFlowRule::recentSubmitDescriptions
                        ).test(runtime, step, rule, desc, pageName)) {
                            runtime.addStep(rewritten);
                        }
                        return TraceWorkflowOutcome.none();
                    }
            ).advanceAfter(),
            TraceWorkflowTransition.<TraceRuleSetConfig.EntryNavigationSubmitFlowRule>of(
                    "notificationChoice",
                    TraceWorkflowConditions.all(
                            TraceWorkflowConditions.hasFlag(FLAG_IN_FLOW),
                            TraceWorkflowConditions.descriptionChoiceExists(
                                    TraceRuleSetConfig.EntryNavigationSubmitFlowRule::notificationChoiceDescriptions,
                                    TraceRuleSetConfig.EntryNavigationSubmitFlowRule::notificationChoiceRewrites
                            )
                    ),
                    (runtime, step, rule, desc, pageName) -> {
                        String rewrite = TraceWorkflowSupport.rewriteChoice(desc, rule.notificationChoiceDescriptions(), rule.notificationChoiceRewrites());
                        runtime.addRewriteFromCurrent(rewrite);
                        return TraceWorkflowOutcome.none();
                    }
            ).advanceAfter(),
            TraceWorkflowTransition.<TraceRuleSetConfig.EntryNavigationSubmitFlowRule>of(
                    "languageChoice",
                    TraceWorkflowConditions.all(
                            TraceWorkflowConditions.hasFlag(FLAG_IN_FLOW),
                            TraceWorkflowConditions.descriptionChoiceExists(
                                    TraceRuleSetConfig.EntryNavigationSubmitFlowRule::languageChoiceDescriptions,
                                    TraceRuleSetConfig.EntryNavigationSubmitFlowRule::languageChoiceRewrites
                            )
                    ),
                    (runtime, step, rule, desc, pageName) -> {
                        String rewrite = TraceWorkflowSupport.rewriteChoice(desc, rule.languageChoiceDescriptions(), rule.languageChoiceRewrites());
                        runtime.addRewriteFromCurrent(rewrite);
                        return TraceWorkflowOutcome.none();
                    }
            ).advanceAfter()
    );
    private static final List<TraceWorkflowTransition<TraceRuleSetConfig.EntryNavigationSubmitFlowRule>> POST_TRANSITIONS = List.of(
            TraceWorkflowTransition.<TraceRuleSetConfig.EntryNavigationSubmitFlowRule>of(
                    "exitOnSourceNavigation",
                    TraceWorkflowConditions.all(
                            TraceWorkflowConditions.actionType("NAVIGATION"),
                            TraceWorkflowConditions.pageContains(TraceRuleSetConfig.EntryNavigationSubmitFlowRule::sourcePageContains)
                    ),
                    (runtime, step, rule, desc, pageName) -> TraceWorkflowOutcome.none()
            ).clearFlagsAfter(FLAG_IN_FLOW, FLAG_TRIGGER_PENDING).advanceAfter(),
            TraceWorkflowTransition.<TraceRuleSetConfig.EntryNavigationSubmitFlowRule>of(
                    "exitOnSessionStop",
                    TraceWorkflowConditions.actionType("SESSION_STOP"),
                    (runtime, step, rule, desc, pageName) -> TraceWorkflowOutcome.endFlow()
            ).clearFlagsAfter(FLAG_IN_FLOW, FLAG_TRIGGER_PENDING).advanceAfter()
    );

    @Override
    public String kind() {
        return "entryNavigationSubmitFlow";
    }

    @Override
    public List<TraceStepNormalizer.CleanTraceStep> apply(
            List<TraceStepNormalizer.CleanTraceStep> steps,
            TraceRuleSetConfig config,
            String workflowRef
    ) {
        TraceRuleSetConfig.EntryNavigationSubmitFlowRule rule = config.resolveEntryNavigationSubmitFlow(workflowRef);
        if (rule == null) {
            return steps;
        }
        return TraceWorkflowEngine.run(
                steps,
                rule,
                List.of(),
                TRANSITIONS,
                POST_TRANSITIONS,
                (runtime, step) -> runtime.addCurrent()
        );
    }
}

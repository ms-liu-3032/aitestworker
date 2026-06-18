package com.company.aitest.trace;

import java.util.List;

final class DialogInputConfirmWorkflowProcessor implements TraceWorkflowProcessor {

    private static final String FLAG_IN_FLOW = "inFlow";
    private static final String FLAG_SAW_SEND_SMS = "sawSendSms";
    private static final String FLAG_SAW_EXPLICIT_NO_SEND = "sawExplicitNoSend";
    private static final String FLAG_AWAITING_INPUT = "awaitingInput";
    private static final TraceWorkflowTransition.Condition<TraceRuleSetConfig.DialogInputConfirmFlowRule> REASON_INPUT_CONDITION =
            TraceWorkflowConditions.all(
                    TraceWorkflowConditions.actionType("INPUT"),
                    TraceWorkflowConditions.pageContains(TraceRuleSetConfig.DialogInputConfirmFlowRule::activePageContains),
                    TraceWorkflowConditions.any(
                            TraceWorkflowConditions.descriptionStartsWith(TraceRuleSetConfig.DialogInputConfirmFlowRule::inputPrefix),
                            TraceWorkflowConditions.descriptionMatchesRegexLiteral("^在“[^”]+”输入.+$")
                    )
            );
    private static final TraceWorkflowTransition.Condition<TraceRuleSetConfig.DialogInputConfirmFlowRule> ENTRY_CANDIDATE_CONDITION =
            TraceWorkflowConditions.all(
                    TraceWorkflowConditions.actionType("CLICK"),
                    TraceWorkflowConditions.pageContains(TraceRuleSetConfig.DialogInputConfirmFlowRule::sourcePageContains),
                    TraceWorkflowConditions.not(TraceWorkflowConditions.descriptionContainsAny(TraceRuleSetConfig.DialogInputConfirmFlowRule::detailProbeDescriptions)),
                    TraceWorkflowConditions.not(TraceWorkflowConditions.descriptionContainsAny(TraceRuleSetConfig.DialogInputConfirmFlowRule::entryExcludeContains)),
                    TraceWorkflowConditions.not(TraceWorkflowConditions.descriptionStartsWith(TraceRuleSetConfig.DialogInputConfirmFlowRule::confirmRewrite)),
                    TraceWorkflowConditions.descriptionStartsWithLiteral("点击“"),
                    TraceWorkflowConditions.hasAheadDescriptionContainsAny(
                            1,
                            flowRule -> TraceWorkflowSupport.defaultInt(flowRule.sourceLookaheadDistance(), 4),
                            TraceRuleSetConfig.DialogInputConfirmFlowRule::sourceDescriptionContainsAny
                    )
            );
    private static final TraceWorkflowTransition.Condition<TraceRuleSetConfig.DialogInputConfirmFlowRule> REASON_FOCUS_CLICK_CONDITION =
            TraceWorkflowConditions.all(
                    TraceWorkflowConditions.actionType("CLICK"),
                    TraceWorkflowConditions.descriptionEqualsAny(TraceRuleSetConfig.DialogInputConfirmFlowRule::focusClickDescriptions)
            );
    private static final TraceWorkflowTransition.Condition<TraceRuleSetConfig.DialogInputConfirmFlowRule> REASON_TYPING_NOISE_CONDITION =
            TraceWorkflowConditions.all(
                    TraceWorkflowConditions.actionType("KEYDOWN"),
                    TraceWorkflowConditions.descriptionContainsAny(TraceRuleSetConfig.DialogInputConfirmFlowRule::typingNoiseContains)
            );
    private static final TraceWorkflowTransition.Condition<TraceRuleSetConfig.DialogInputConfirmFlowRule> DIALOG_OPERATION_NOISE_CONDITION =
            TraceWorkflowConditions.all(
                    TraceWorkflowConditions.pageContains(TraceRuleSetConfig.DialogInputConfirmFlowRule::activePageContains),
                    TraceWorkflowConditions.any(
                            TraceWorkflowConditions.all(
                                    TraceWorkflowConditions.actionType("KEYDOWN"),
                                    TraceWorkflowConditions.descriptionContainsAny(TraceRuleSetConfig.DialogInputConfirmFlowRule::typingNoiseContains)
                            ),
                            TraceWorkflowConditions.all(
                                    TraceWorkflowConditions.actionTypeAny("CLICK", "CHANGE"),
                                    TraceWorkflowConditions.any(
                                            TraceWorkflowConditions.descriptionContainsAny(TraceRuleSetConfig.DialogInputConfirmFlowRule::dialogNoiseContains),
                                            TraceWorkflowConditions.descriptionMatchesAnyRegex(TraceRuleSetConfig.DialogInputConfirmFlowRule::dialogNoiseRegexes),
                                            TraceWorkflowConditions.all(
                                                    TraceWorkflowConditions.descriptionContainsAny(TraceRuleSetConfig.DialogInputConfirmFlowRule::detailProbeDescriptions),
                                                    TraceWorkflowConditions.any(
                                                            TraceWorkflowConditions.hasAheadDescriptionContainsAny(
                                                                    1,
                                                                    flowRule -> 3,
                                                                    TraceRuleSetConfig.DialogInputConfirmFlowRule::sourceDescriptionContainsAny
                                                            ),
                                                            TraceWorkflowConditions.hasAheadDescriptionContainsAny(
                                                                    0,
                                                                    flowRule -> 3,
                                                                    TraceRuleSetConfig.DialogInputConfirmFlowRule::sourceDescriptionContainsAny
                                                            )
                                                    )
                                            )
                                    )
                            )
                    )
            );
    private static final List<TraceWorkflowTransition<TraceRuleSetConfig.DialogInputConfirmFlowRule>> PRE_TRANSITIONS = List.of(
            TraceWorkflowTransition.<TraceRuleSetConfig.DialogInputConfirmFlowRule>of(
                    "primeFromStartDescription",
                    TraceWorkflowConditions.descriptionEquals(TraceRuleSetConfig.DialogInputConfirmFlowRule::startDescription),
                    (runtime, step, rule, desc, pageName) -> TraceWorkflowOutcome.passThrough()
            ).clearFlagsAfter(FLAG_SAW_SEND_SMS, FLAG_SAW_EXPLICIT_NO_SEND)
                    .setFlagsAfter(FLAG_IN_FLOW, FLAG_AWAITING_INPUT),
            TraceWorkflowTransition.<TraceRuleSetConfig.DialogInputConfirmFlowRule>of(
                    "primeFromActivePage",
                    TraceWorkflowConditions.pageContains(TraceRuleSetConfig.DialogInputConfirmFlowRule::activePageContains),
                    (runtime, step, rule, desc, pageName) -> TraceWorkflowOutcome.passThrough()
            ).setFlagsAfter(FLAG_IN_FLOW)
    );
    private static final List<TraceWorkflowTransition<TraceRuleSetConfig.DialogInputConfirmFlowRule>> TRANSITIONS = List.of(
            TraceWorkflowTransition.<TraceRuleSetConfig.DialogInputConfirmFlowRule>of(
                    "dialogNoise",
                    DIALOG_OPERATION_NOISE_CONDITION,
                    (runtime, step, rule, desc, pageName) -> TraceWorkflowOutcome.none()
            ).advanceAfter(),
            TraceWorkflowTransition.<TraceRuleSetConfig.DialogInputConfirmFlowRule>of(
                    "entryCandidateWhileAwaitingInput",
                    TraceWorkflowConditions.all(
                            ENTRY_CANDIDATE_CONDITION,
                            TraceWorkflowConditions.hasFlag(FLAG_AWAITING_INPUT)
                    ),
                    (runtime, step, rule, desc, pageName) -> TraceWorkflowOutcome.none()
            ).advanceAfter(),
            TraceWorkflowTransition.<TraceRuleSetConfig.DialogInputConfirmFlowRule>of(
                    "entryCandidate",
                    TraceWorkflowConditions.all(
                            ENTRY_CANDIDATE_CONDITION,
                            TraceWorkflowConditions.not(TraceWorkflowConditions.hasFlag(FLAG_AWAITING_INPUT))
                    ),
                    (runtime, step, rule, desc, pageName) -> {
                        if (!runtime.hasLastResult()
                                || !TraceWorkflowSupport.defaultText(rule.startDescription(), "").equals(runtime.lastResult().description())) {
                            runtime.addRewrite("CLICK", TraceWorkflowSupport.defaultText(rule.startDescription(), desc), step.relativeMs());
                        }
                        return TraceWorkflowOutcome.none();
                    }
            ).clearFlagsAfter(FLAG_SAW_SEND_SMS, FLAG_SAW_EXPLICIT_NO_SEND).setFlagsAfter(FLAG_AWAITING_INPUT).advanceAfter(),
            TraceWorkflowTransition.<TraceRuleSetConfig.DialogInputConfirmFlowRule>of(
                    "reasonInput",
                    REASON_INPUT_CONDITION,
                    (runtime, step, rule, desc, pageName) -> {
                        String finalValue = TraceWorkflowSupport.selectBestInputValue(
                                desc,
                                null,
                                rule.inputPrefix(),
                                rule.placeholderTokens(),
                                rule.fallbackValue()
                        );
                        int end = runtime.consumeFollowingWhile(next ->
                                REASON_FOCUS_CLICK_CONDITION.test(runtime, next, rule,
                                        TraceWorkflowSupport.defaultText(next.description(), ""),
                                        TraceWorkflowSupport.sanitize(next.pageName()))
                                        || REASON_TYPING_NOISE_CONDITION.test(runtime, next, rule,
                                        TraceWorkflowSupport.defaultText(next.description(), ""),
                                        TraceWorkflowSupport.sanitize(next.pageName()))
                                        || REASON_INPUT_CONDITION.test(runtime, next, rule,
                                        TraceWorkflowSupport.defaultText(next.description(), ""),
                                        TraceWorkflowSupport.sanitize(next.pageName())));
                        for (int cursor = runtime.index() + 1; cursor <= end; cursor++) {
                            TraceStepNormalizer.CleanTraceStep next = runtime.sourceAt(cursor);
                            if (REASON_INPUT_CONDITION.test(runtime, next, rule,
                                    TraceWorkflowSupport.defaultText(next.description(), ""),
                                    TraceWorkflowSupport.sanitize(next.pageName()))) {
                                finalValue = TraceWorkflowSupport.selectBestInputValue(
                                        next.description(),
                                        finalValue,
                                        rule.inputPrefix(),
                                        rule.placeholderTokens(),
                                        rule.fallbackValue()
                                );
                            }
                        }
                        if (finalValue == null || finalValue.isBlank()) {
                            finalValue = TraceWorkflowSupport.defaultText(rule.fallbackValue(), "输入内容");
                        }
                        TraceStepNormalizer.CleanTraceStep mergedInput = new TraceStepNormalizer.CleanTraceStep(
                                0, step.actor(), "INPUT",
                                TraceWorkflowSupport.defaultText(rule.mergedInputTemplate(), "%s").formatted(finalValue),
                                step.pageName(), step.pageUrl(), runtime.sourceAt(end).relativeMs()
                        );
                        if (!runtime.hasLastResult() || !TraceWorkflowSupport.sameStepMeaning(runtime.lastResult(), mergedInput)) {
                            runtime.addStep(mergedInput);
                        }
                        return TraceWorkflowOutcome.consumeTo(end);
                    }
            ).clearFlagsAfter(FLAG_AWAITING_INPUT),
            TraceWorkflowTransition.<TraceRuleSetConfig.DialogInputConfirmFlowRule>of(
                    "sendSmsChoice",
                    TraceWorkflowConditions.descriptionContainsAny(TraceRuleSetConfig.DialogInputConfirmFlowRule::sendSmsDescriptions),
                    (runtime, step, rule, desc, pageName) -> {
                        runtime.addCurrent();
                        return TraceWorkflowOutcome.none();
                    }
            ).clearFlagsAfter(FLAG_AWAITING_INPUT).setFlagsAfter(FLAG_SAW_SEND_SMS).advanceAfter(),
            TraceWorkflowTransition.<TraceRuleSetConfig.DialogInputConfirmFlowRule>of(
                    "explicitNoSendChoice",
                    TraceWorkflowConditions.descriptionContainsAny(TraceRuleSetConfig.DialogInputConfirmFlowRule::explicitNoSendDescriptions),
                    (runtime, step, rule, desc, pageName) -> {
                        runtime.addCurrent();
                        return TraceWorkflowOutcome.none();
                    }
            ).clearFlagsAfter(FLAG_AWAITING_INPUT).setFlagsAfter(FLAG_SAW_EXPLICIT_NO_SEND).advanceAfter(),
            TraceWorkflowTransition.<TraceRuleSetConfig.DialogInputConfirmFlowRule>of(
                    "cancel",
                    TraceWorkflowConditions.descriptionContainsAny(TraceRuleSetConfig.DialogInputConfirmFlowRule::cancelDescriptions),
                    (runtime, step, rule, desc, pageName) -> {
                        runtime.addRewriteFromCurrent(TraceWorkflowSupport.defaultText(rule.cancelRewrite(), desc));
                        return TraceWorkflowOutcome.none();
                    }
            ).clearFlagsAfter(FLAG_IN_FLOW, FLAG_SAW_SEND_SMS, FLAG_SAW_EXPLICIT_NO_SEND, FLAG_AWAITING_INPUT).advanceAfter(),
            TraceWorkflowTransition.<TraceRuleSetConfig.DialogInputConfirmFlowRule>of(
                    "confirm",
                    TraceWorkflowConditions.descriptionContainsAny(TraceRuleSetConfig.DialogInputConfirmFlowRule::confirmDescriptions),
                    (runtime, step, rule, desc, pageName) -> {
                        String rewritten = TraceWorkflowSupport.defaultText(rule.confirmRewrite(), desc);
                        TraceWorkflowOutcome outcome = TraceWorkflowOutcome.none();
                        if (runtime.hasFlag(FLAG_IN_FLOW) && !runtime.hasFlag(FLAG_SAW_SEND_SMS) && !runtime.hasFlag(FLAG_SAW_EXPLICIT_NO_SEND)) {
                            rewritten = TraceWorkflowSupport.defaultText(rule.implicitConfirmRewrite(), rewritten);
                            outcome = outcome.setFlags(FLAG_SAW_EXPLICIT_NO_SEND);
                        }
                        if (runtime.hasLastResult()) {
                            TraceStepNormalizer.CleanTraceStep last = runtime.lastResult();
                            if (TraceWorkflowSupport.defaultText(rule.confirmRewrite(), "").equals(last.description())
                                    || TraceWorkflowSupport.defaultText(rule.implicitConfirmRewrite(), "").equals(last.description())) {
                                return outcome;
                            }
                            if (TraceWorkflowSupport.contains(rule.successDescriptions(), last.description())) {
                                runtime.removeLastResult();
                                runtime.addRewriteFromCurrent(rewritten);
                                runtime.addStep(last);
                                return outcome;
                            }
                        }
                        runtime.addRewriteFromCurrent(rewritten);
                        return outcome;
                    }
            ).clearFlagsAfter(FLAG_IN_FLOW, FLAG_AWAITING_INPUT).advanceAfter(),
            TraceWorkflowTransition.<TraceRuleSetConfig.DialogInputConfirmFlowRule>of(
                    "confirmWithSuffix",
                    TraceWorkflowConditions.descriptionStartsWith(TraceRuleSetConfig.DialogInputConfirmFlowRule::confirmRewrite),
                    (runtime, step, rule, desc, pageName) -> {
                        String rewritten = TraceWorkflowSupport.stripSuffixTokens(desc, rule.confirmSuffixStripTokens());
                        if (runtime.hasLastResult()) {
                            TraceStepNormalizer.CleanTraceStep last = runtime.lastResult();
                            String lastDesc = TraceWorkflowSupport.defaultText(last.description(), "");
                            if (lastDesc.equals(rewritten)
                                    || lastDesc.startsWith(TraceWorkflowSupport.defaultText(rule.confirmRewrite(), ""))
                                    || lastDesc.startsWith(TraceWorkflowSupport.defaultText(rule.implicitConfirmRewrite(), ""))) {
                                return TraceWorkflowOutcome.none();
                            }
                        }
                        runtime.addRewriteFromCurrent(rewritten);
                        return TraceWorkflowOutcome.none();
                    }
            ).clearFlagsAfter(FLAG_IN_FLOW, FLAG_AWAITING_INPUT).advanceAfter(),
            TraceWorkflowTransition.<TraceRuleSetConfig.DialogInputConfirmFlowRule>of(
                    "success",
                    TraceWorkflowConditions.descriptionStartsWithAny(TraceRuleSetConfig.DialogInputConfirmFlowRule::successPrefixes),
                    (runtime, step, rule, desc, pageName) -> {
                        if (runtime.hasFlag(FLAG_IN_FLOW) && runtime.hasLastResult()) {
                            TraceStepNormalizer.CleanTraceStep last = runtime.lastResult();
                            if (TraceWorkflowSupport.contains(rule.successDescriptions(), last.description())) {
                                return TraceWorkflowOutcome.none();
                            }
                        }
                        runtime.addCurrent();
                        return TraceWorkflowOutcome.none();
                    }
            ).advanceAfter()
    );
    private static final List<TraceWorkflowTransition<TraceRuleSetConfig.DialogInputConfirmFlowRule>> POST_TRANSITIONS = List.of(
            TraceWorkflowTransition.<TraceRuleSetConfig.DialogInputConfirmFlowRule>of(
                    "exitOnSessionStop",
                    TraceWorkflowConditions.actionType("SESSION_STOP"),
                    (runtime, step, rule, desc, pageName) -> TraceWorkflowOutcome.endFlow()
            ).clearFlagsAfter(FLAG_IN_FLOW, FLAG_SAW_SEND_SMS, FLAG_SAW_EXPLICIT_NO_SEND, FLAG_AWAITING_INPUT).advanceAfter(),
            TraceWorkflowTransition.<TraceRuleSetConfig.DialogInputConfirmFlowRule>of(
                    "exitOnNavigationAway",
                    TraceWorkflowConditions.all(
                            TraceWorkflowConditions.actionType("NAVIGATION"),
                            TraceWorkflowConditions.not(
                                    TraceWorkflowConditions.pageContains(TraceRuleSetConfig.DialogInputConfirmFlowRule::activePageContains)
                            )
                    ),
                    (runtime, step, rule, desc, pageName) -> TraceWorkflowOutcome.none()
            ).clearFlagsAfter(FLAG_IN_FLOW, FLAG_SAW_SEND_SMS, FLAG_SAW_EXPLICIT_NO_SEND, FLAG_AWAITING_INPUT).advanceAfter()
    );

    @Override
    public String kind() {
        return "dialogInputConfirmFlow";
    }

    @Override
    public List<TraceStepNormalizer.CleanTraceStep> apply(
            List<TraceStepNormalizer.CleanTraceStep> steps,
            TraceRuleSetConfig config,
            String workflowRef
    ) {
        TraceRuleSetConfig.DialogInputConfirmFlowRule rule = config.resolveDialogInputConfirmFlow(workflowRef);
        if (rule == null) {
            return steps;
        }
        return TraceWorkflowEngine.run(
                steps,
                rule,
                PRE_TRANSITIONS,
                TRANSITIONS,
                POST_TRANSITIONS,
                (runtime, step) -> runtime.addCurrent()
        );
    }
}

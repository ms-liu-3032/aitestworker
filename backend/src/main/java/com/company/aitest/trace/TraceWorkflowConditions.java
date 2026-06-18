package com.company.aitest.trace;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

final class TraceWorkflowConditions {

    private TraceWorkflowConditions() {
    }

    @SafeVarargs
    static <R> TraceWorkflowTransition.Condition<R> all(TraceWorkflowTransition.Condition<R>... conditions) {
        return (runtime, step, rule, description, pageName) -> {
            for (TraceWorkflowTransition.Condition<R> condition : conditions) {
                if (!condition.test(runtime, step, rule, description, pageName)) {
                    return false;
                }
            }
            return true;
        };
    }

    @SafeVarargs
    static <R> TraceWorkflowTransition.Condition<R> any(TraceWorkflowTransition.Condition<R>... conditions) {
        return (runtime, step, rule, description, pageName) -> {
            for (TraceWorkflowTransition.Condition<R> condition : conditions) {
                if (condition.test(runtime, step, rule, description, pageName)) {
                    return true;
                }
            }
            return false;
        };
    }

    static <R> TraceWorkflowTransition.Condition<R> not(TraceWorkflowTransition.Condition<R> condition) {
        return (runtime, step, rule, description, pageName) -> !condition.test(runtime, step, rule, description, pageName);
    }

    static <R> TraceWorkflowTransition.Condition<R> hasFlag(String flag) {
        return (runtime, step, rule, description, pageName) -> runtime.hasFlag(flag);
    }

    static <R> TraceWorkflowTransition.Condition<R> actionType(String actionType) {
        return (runtime, step, rule, description, pageName) -> actionType.equals(step.actionType());
    }

    static <R> TraceWorkflowTransition.Condition<R> actionTypeAny(String... actionTypes) {
        return (runtime, step, rule, description, pageName) -> {
            for (String actionType : actionTypes) {
                if (actionType.equals(step.actionType())) {
                    return true;
                }
            }
            return false;
        };
    }

    static <R> TraceWorkflowTransition.Condition<R> pageContains(Function<R, String> selector) {
        return (runtime, step, rule, description, pageName) -> pageName != null
                && pageName.contains(TraceWorkflowSupport.defaultText(selector.apply(rule), ""));
    }

    static <R> TraceWorkflowTransition.Condition<R> descriptionEquals(Function<R, String> selector) {
        return (runtime, step, rule, description, pageName) ->
                TraceWorkflowSupport.defaultText(selector.apply(rule), "").equals(description);
    }

    static <R> TraceWorkflowTransition.Condition<R> descriptionContains(Function<R, String> selector) {
        return (runtime, step, rule, description, pageName) ->
                description.contains(TraceWorkflowSupport.defaultText(selector.apply(rule), ""));
    }

    static <R> TraceWorkflowTransition.Condition<R> descriptionStartsWith(Function<R, String> selector) {
        return (runtime, step, rule, description, pageName) ->
                description.startsWith(TraceWorkflowSupport.defaultText(selector.apply(rule), ""));
    }

    static <R> TraceWorkflowTransition.Condition<R> descriptionStartsWithLiteral(String prefix) {
        return (runtime, step, rule, description, pageName) ->
                description.startsWith(TraceWorkflowSupport.defaultText(prefix, ""));
    }

    static <R> TraceWorkflowTransition.Condition<R> descriptionContainsAny(Function<R, List<String>> selector) {
        return (runtime, step, rule, description, pageName) ->
                TraceWorkflowSupport.containsAny(description, selector.apply(rule));
    }

    static <R> TraceWorkflowTransition.Condition<R> descriptionEqualsAny(Function<R, List<String>> selector) {
        return (runtime, step, rule, description, pageName) ->
                TraceWorkflowSupport.contains(selector.apply(rule), description);
    }

    static <R> TraceWorkflowTransition.Condition<R> descriptionStartsWithAny(Function<R, List<String>> selector) {
        return (runtime, step, rule, description, pageName) ->
                TraceWorkflowSupport.startsWithAny(description, selector.apply(rule));
    }

    static <R> TraceWorkflowTransition.Condition<R> descriptionMatchesAnyRegex(Function<R, List<String>> selector) {
        return (runtime, step, rule, description, pageName) ->
                TraceWorkflowSupport.matchesAnyRegex(description, selector.apply(rule));
    }

    static <R> TraceWorkflowTransition.Condition<R> descriptionMatchesRegexLiteral(String regex) {
        return (runtime, step, rule, description, pageName) ->
                TraceWorkflowSupport.defaultText(description, "").matches(regex);
    }

    static <R> TraceWorkflowTransition.Condition<R> descriptionChoiceExists(
            Function<R, List<String>> descriptionsSelector,
            Function<R, List<String>> rewritesSelector
    ) {
        return (runtime, step, rule, description, pageName) ->
                TraceWorkflowSupport.rewriteChoice(description, descriptionsSelector.apply(rule), rewritesSelector.apply(rule)) != null;
    }

    static <R> TraceWorkflowTransition.Condition<R> hasAhead(
            int startOffset,
            ToIntFunction<R> distanceSelector,
            Predicate<TraceStepNormalizer.CleanTraceStep> predicate
    ) {
        return (runtime, step, rule, description, pageName) ->
                runtime.hasAhead(startOffset, Math.max(distanceSelector.applyAsInt(rule), 0), predicate);
    }

    static <R> TraceWorkflowTransition.Condition<R> hasAheadDescriptionContainsAny(
            int startOffset,
            ToIntFunction<R> distanceSelector,
            Function<R, List<String>> selector
    ) {
        return (runtime, step, rule, description, pageName) ->
                runtime.hasAhead(
                        startOffset,
                        Math.max(distanceSelector.applyAsInt(rule), 0),
                        candidate -> TraceWorkflowSupport.containsAny(
                                TraceWorkflowSupport.defaultText(candidate.description(), ""),
                                selector.apply(rule)
                        )
                );
    }

    static <R> TraceWorkflowTransition.Condition<R> hasAheadDescriptionEqualsAny(
            int startOffset,
            ToIntFunction<R> distanceSelector,
            Function<R, List<String>> selector
    ) {
        return (runtime, step, rule, description, pageName) ->
                runtime.hasAhead(
                        startOffset,
                        Math.max(distanceSelector.applyAsInt(rule), 0),
                        candidate -> TraceWorkflowSupport.contains(
                                selector.apply(rule),
                                TraceWorkflowSupport.defaultText(candidate.description(), "")
                        )
                );
    }

    static <R> TraceWorkflowTransition.Condition<R> hasAheadDescriptionStartsWithAny(
            int startOffset,
            ToIntFunction<R> distanceSelector,
            Function<R, List<String>> selector
    ) {
        return (runtime, step, rule, description, pageName) ->
                runtime.hasAhead(
                        startOffset,
                        Math.max(distanceSelector.applyAsInt(rule), 0),
                        candidate -> TraceWorkflowSupport.startsWithAny(
                                TraceWorkflowSupport.defaultText(candidate.description(), ""),
                                selector.apply(rule)
                        )
                );
    }

    static <R> TraceWorkflowTransition.Condition<R> hasAheadNavigationDescriptionContains(
            int startOffset,
            ToIntFunction<R> distanceSelector,
            Function<R, String> selector
    ) {
        return (runtime, step, rule, description, pageName) ->
                runtime.hasAhead(
                        startOffset,
                        Math.max(distanceSelector.applyAsInt(rule), 0),
                        candidate -> "NAVIGATION".equals(candidate.actionType())
                                && TraceWorkflowSupport.defaultText(candidate.description(), "").startsWith("跳转到页面“")
                                && TraceWorkflowSupport.defaultText(candidate.description(), "")
                                .contains(TraceWorkflowSupport.defaultText(selector.apply(rule), ""))
                );
    }

    static <R> TraceWorkflowTransition.Condition<R> recentResultDescriptionContainsAny(
            int limit,
            Function<R, List<String>> selector
    ) {
        return (runtime, step, rule, description, pageName) ->
                runtime.hasRecentResult(limit, resultStep ->
                        TraceWorkflowSupport.contains(
                                selector.apply(rule),
                                TraceWorkflowSupport.defaultText(resultStep.description(), "")
                        ));
    }

    static <R> TraceWorkflowTransition.Condition<R> lastResultDescriptionContainsAny(Function<R, List<String>> selector) {
        return (runtime, step, rule, description, pageName) ->
                runtime.hasLastResult()
                        && TraceWorkflowSupport.contains(
                        selector.apply(rule),
                        TraceWorkflowSupport.defaultText(runtime.lastResult().description(), "")
                );
    }
}

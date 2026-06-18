package com.company.aitest.trace;

import java.util.List;

final class TraceWorkflowTransition<R> {

    record ApplyResult(boolean matched, boolean passThrough) {
        static ApplyResult notMatched() {
            return new ApplyResult(false, false);
        }

        static ApplyResult handled(boolean passThrough) {
            return new ApplyResult(true, passThrough);
        }
    }

    @FunctionalInterface
    interface Condition<R> {
        boolean test(TraceWorkflowRuntime runtime,
                     TraceStepNormalizer.CleanTraceStep step,
                     R rule,
                     String description,
                     String pageName);
    }

    @FunctionalInterface
    interface Action<R> {
        TraceWorkflowOutcome apply(TraceWorkflowRuntime runtime,
                                   TraceStepNormalizer.CleanTraceStep step,
                                   R rule,
                                   String description,
                                   String pageName);
    }

    private final String name;
    private final Condition<R> condition;
    private final Action<R> action;
    private final boolean advanceAfter;
    private final List<String> setFlagsAfter;
    private final List<String> clearFlagsAfter;

    private TraceWorkflowTransition(String name,
                                    Condition<R> condition,
                                    Action<R> action,
                                    boolean advanceAfter,
                                    List<String> setFlagsAfter,
                                    List<String> clearFlagsAfter) {
        this.name = name;
        this.condition = condition;
        this.action = action;
        this.advanceAfter = advanceAfter;
        this.setFlagsAfter = setFlagsAfter;
        this.clearFlagsAfter = clearFlagsAfter;
    }

    static <R> TraceWorkflowTransition<R> of(String name, Condition<R> condition, Action<R> action) {
        return new TraceWorkflowTransition<>(name, condition, action, false, List.of(), List.of());
    }

    TraceWorkflowTransition<R> advanceAfter() {
        return new TraceWorkflowTransition<>(name, condition, action, true, setFlagsAfter, clearFlagsAfter);
    }

    TraceWorkflowTransition<R> setFlagsAfter(String... flags) {
        return new TraceWorkflowTransition<>(name, condition, action, advanceAfter, List.of(flags), clearFlagsAfter);
    }

    TraceWorkflowTransition<R> clearFlagsAfter(String... flags) {
        return new TraceWorkflowTransition<>(name, condition, action, advanceAfter, setFlagsAfter, List.of(flags));
    }

    ApplyResult apply(TraceWorkflowRuntime runtime,
                      TraceStepNormalizer.CleanTraceStep step,
                      R rule,
                      String description,
                      String pageName) {
        if (!condition.test(runtime, step, rule, description, pageName)) {
            return ApplyResult.notMatched();
        }
        TraceWorkflowOutcome outcome = action.apply(runtime, step, rule, description, pageName);
        if (outcome == null) {
            outcome = TraceWorkflowOutcome.none();
        }
        for (String flag : outcome.clearFlags()) {
            runtime.clearFlag(flag);
        }
        for (String flag : outcome.setFlags()) {
            runtime.setFlag(flag);
        }
        for (String flag : clearFlagsAfter) {
            runtime.clearFlag(flag);
        }
        for (String flag : setFlagsAfter) {
            runtime.setFlag(flag);
        }
        if (outcome.shouldEndFlow()) {
            runtime.end();
        } else if (outcome.jumpToIndex() != null) {
            runtime.jumpTo(outcome.jumpToIndex());
        } else if (advanceAfter) {
            runtime.next();
        }
        return ApplyResult.handled(outcome.shouldPassThrough());
    }

    String name() {
        return name;
    }
}

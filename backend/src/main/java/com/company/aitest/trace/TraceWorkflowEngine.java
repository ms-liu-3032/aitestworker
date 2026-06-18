package com.company.aitest.trace;

import java.util.List;
import java.util.function.BiConsumer;

final class TraceWorkflowEngine {

    private TraceWorkflowEngine() {
    }

    static <R> List<TraceStepNormalizer.CleanTraceStep> run(
            List<TraceStepNormalizer.CleanTraceStep> steps,
            R rule,
            List<TraceWorkflowTransition<R>> preTransitions,
            List<TraceWorkflowTransition<R>> transitions,
            List<TraceWorkflowTransition<R>> postTransitions,
            BiConsumer<TraceWorkflowRuntime, TraceStepNormalizer.CleanTraceStep> defaultAction
    ) {
        TraceWorkflowRuntime runtime = new TraceWorkflowRuntime(steps);
        while (runtime.hasCurrent()) {
            TraceStepNormalizer.CleanTraceStep step = runtime.current();
            String pageName = TraceWorkflowSupport.sanitize(step.pageName());
            String desc = TraceWorkflowSupport.defaultText(step.description(), "");

            TraceWorkflowTransition.ApplyResult preResult = applyFirst(preTransitions, runtime, step, rule, desc, pageName);
            if (preResult.matched() && !preResult.passThrough()) {
                continue;
            }
            if (!runtime.hasCurrent()) {
                break;
            }
            step = runtime.current();
            pageName = TraceWorkflowSupport.sanitize(step.pageName());
            desc = TraceWorkflowSupport.defaultText(step.description(), "");

            TraceWorkflowTransition.ApplyResult transitionResult = applyFirst(transitions, runtime, step, rule, desc, pageName);
            if (transitionResult.matched() && !transitionResult.passThrough()) {
                continue;
            }

            if (!runtime.hasCurrent()) {
                break;
            }
            step = runtime.current();
            pageName = TraceWorkflowSupport.sanitize(step.pageName());
            desc = TraceWorkflowSupport.defaultText(step.description(), "");

            defaultAction.accept(runtime, step);
            TraceWorkflowTransition.ApplyResult postResult = applyFirst(postTransitions, runtime, step, rule, desc, pageName);
            if (postResult.matched() && !postResult.passThrough()) {
                continue;
            }
            runtime.next();
        }
        return runtime.result();
    }

    private static <R> TraceWorkflowTransition.ApplyResult applyFirst(List<TraceWorkflowTransition<R>> transitions,
                                                                      TraceWorkflowRuntime runtime,
                                                                      TraceStepNormalizer.CleanTraceStep step,
                                                                      R rule,
                                                                      String desc,
                                                                      String pageName) {
        for (TraceWorkflowTransition<R> transition : TraceWorkflowSupport.safeList(transitions)) {
            TraceWorkflowTransition.ApplyResult result = transition.apply(runtime, step, rule, desc, pageName);
            if (result.matched()) {
                return result;
            }
        }
        return TraceWorkflowTransition.ApplyResult.notMatched();
    }
}

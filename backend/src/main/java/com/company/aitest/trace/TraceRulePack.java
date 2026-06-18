package com.company.aitest.trace;

import java.util.List;

interface TraceRulePack {

    default DescriptionDecision describeInput(TraceDescriptionContext context) {
        return DescriptionDecision.pass();
    }

    default DescriptionDecision describeChange(TraceDescriptionContext context) {
        return DescriptionDecision.pass();
    }

    default DescriptionDecision describeClick(TraceDescriptionContext context) {
        return DescriptionDecision.pass();
    }

    default List<TraceStepNormalizer.CleanTraceStep> postProcess(List<TraceStepNormalizer.CleanTraceStep> steps) {
        return steps;
    }

    default List<TraceStepNormalizer.CleanTraceStep> postProcess(Long projectId, List<TraceStepNormalizer.CleanTraceStep> steps) {
        return postProcess(steps);
    }

    default String suggestObjectLabel(BrowserTraceEventRecord event) {
        return null;
    }

    default String suggestDialogAction(BrowserTraceEventRecord event) {
        return null;
    }

    default String suggestBusinessAction(BrowserTraceEventRecord event) {
        return null;
    }

    default String suggestCheckboxSemantics(BrowserTraceEventRecord event) {
        return null;
    }

    record DescriptionDecision(boolean handled, String description) {
        static DescriptionDecision pass() {
            return new DescriptionDecision(false, null);
        }

        static DescriptionDecision replace(String description) {
            return new DescriptionDecision(true, description);
        }

        static DescriptionDecision drop() {
            return new DescriptionDecision(true, null);
        }
    }
}

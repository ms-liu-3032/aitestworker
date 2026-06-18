package com.company.aitest.trace;

import java.util.List;

interface TraceWorkflowProcessor {

    String kind();

    List<TraceStepNormalizer.CleanTraceStep> apply(
            List<TraceStepNormalizer.CleanTraceStep> steps,
            TraceRuleSetConfig config,
            String workflowRef
    );
}

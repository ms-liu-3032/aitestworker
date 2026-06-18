package com.company.aitest.trace;

import java.util.ArrayList;
import java.util.List;

final class TraceWorkflowOutcome {

    private static final TraceWorkflowOutcome NONE = new TraceWorkflowOutcome(null, List.of(), List.of(), false, false);
    private static final TraceWorkflowOutcome PASS_THROUGH = new TraceWorkflowOutcome(null, List.of(), List.of(), true, false);
    private static final TraceWorkflowOutcome END_FLOW = new TraceWorkflowOutcome(null, List.of(), List.of(), false, true);

    private final Integer jumpToIndex;
    private final List<String> setFlags;
    private final List<String> clearFlags;
    private final boolean passThrough;
    private final boolean endFlow;

    private TraceWorkflowOutcome(Integer jumpToIndex, List<String> setFlags, List<String> clearFlags, boolean passThrough, boolean endFlow) {
        this.jumpToIndex = jumpToIndex;
        this.setFlags = setFlags;
        this.clearFlags = clearFlags;
        this.passThrough = passThrough;
        this.endFlow = endFlow;
    }

    static TraceWorkflowOutcome none() {
        return NONE;
    }

    static TraceWorkflowOutcome passThrough() {
        return PASS_THROUGH;
    }

    static TraceWorkflowOutcome endFlow() {
        return END_FLOW;
    }

    static TraceWorkflowOutcome jumpTo(int nextIndex) {
        return new TraceWorkflowOutcome(nextIndex, List.of(), List.of(), false, false);
    }

    static TraceWorkflowOutcome consumeTo(int lastConsumedIndex) {
        return jumpTo(lastConsumedIndex + 1);
    }

    TraceWorkflowOutcome setFlags(String... flags) {
        List<String> merged = new ArrayList<>(setFlags);
        merged.addAll(List.of(flags));
        return new TraceWorkflowOutcome(jumpToIndex, List.copyOf(merged), clearFlags, passThrough, endFlow);
    }

    TraceWorkflowOutcome clearFlags(String... flags) {
        List<String> merged = new ArrayList<>(clearFlags);
        merged.addAll(List.of(flags));
        return new TraceWorkflowOutcome(jumpToIndex, setFlags, List.copyOf(merged), passThrough, endFlow);
    }

    Integer jumpToIndex() {
        return jumpToIndex;
    }

    List<String> setFlags() {
        return setFlags;
    }

    List<String> clearFlags() {
        return clearFlags;
    }

    boolean shouldPassThrough() {
        return passThrough;
    }

    boolean shouldEndFlow() {
        return endFlow;
    }
}

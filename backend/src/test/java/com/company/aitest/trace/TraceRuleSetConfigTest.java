package com.company.aitest.trace;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceRuleSetConfigTest {

    @Test
    void shouldResolveNamedDialogInputConfirmFlowByRef() {
        TraceRuleSetConfig config = new TraceRuleSetConfig(
                "test",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new TraceRuleSetConfig.DialogInputConfirmFlowRule(
                                "cancel-flow",
                                "start", "active", "source", List.of(), 1,
                                List.of(), "input", "%s", "fallback", List.of(),
                                List.of(), List.of(), List.of(), "cancel",
                                List.of(), "confirm", "implicit",
                                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
                        )
                ),
                List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        assertNotNull(config.resolveDialogInputConfirmFlow("cancel-flow"));
        assertNull(config.resolveDialogInputConfirmFlow("other-flow"));
    }

    @Test
    void shouldResolveNamedEntryNavigationSubmitFlowByRef() {
        TraceRuleSetConfig config = new TraceRuleSetConfig(
                "test",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new TraceRuleSetConfig.EntryNavigationSubmitFlowRule(
                                "modify-flow",
                                "source", "flow", "entry", 1, "nav", "entryRw", "navRw",
                                List.of(), List.of(), List.of(),
                                "confirmPage", List.of(), "confirmRw",
                                List.of(), "submitRw", 1, List.of(), List.of(),
                                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
                        )
                ),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        assertNotNull(config.resolveEntryNavigationSubmitFlow("modify-flow"));
        assertNull(config.resolveEntryNavigationSubmitFlow("other-flow"));
    }

    @Test
    void workflowOutcome_shouldSupportPassThroughAndConsumeToSemantics() {
        TraceWorkflowOutcome passThrough = TraceWorkflowOutcome.passThrough();
        TraceWorkflowOutcome consumeTo = TraceWorkflowOutcome.consumeTo(5);
        TraceWorkflowOutcome endFlow = TraceWorkflowOutcome.endFlow();

        assertTrue(passThrough.shouldPassThrough());
        assertNull(passThrough.jumpToIndex());
        assertTrue(!consumeTo.shouldPassThrough());
        assertNotNull(consumeTo.jumpToIndex());
        assertTrue(consumeTo.jumpToIndex() == 6);
        assertTrue(endFlow.shouldEndFlow());
    }
}

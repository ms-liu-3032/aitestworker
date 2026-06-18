package com.company.aitest.llm.gateway.retrieval;

import org.junit.jupiter.api.Test;

import com.company.aitest.llm.gateway.LlmStage;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalPolicyTest {

    @Test
    void disabledStagesYieldDisabledPolicy() {
        assertTrue(RetrievalPolicy.forStage(LlmStage.SKILL_EXEC, "x").disabled());
        assertTrue(RetrievalPolicy.forStage(LlmStage.OTHER, "x").disabled());
    }

    @Test
    void enabledStagesDefaultExcludeDeprecated() {
        for (LlmStage s : new LlmStage[]{
                LlmStage.REQ_CLARIFY,
                LlmStage.TEST_POINT_GEN,
                LlmStage.TEST_CASE_GEN,
                LlmStage.TRACE_SUMMARY,
                LlmStage.QUALITY_CHECK}) {
            RetrievalPolicy p = RetrievalPolicy.forStage(s, "x");
            assertFalse(p.disabled(), s.name());
            assertFalse(p.includeDeprecated(), s.name() + " should exclude deprecated by default");
            assertFalse(p.includeUnreviewedPublic(), s.name() + " should exclude unreviewed public by default");
        }
    }

    @Test
    void disableFactoryFlags() {
        RetrievalPolicy d = RetrievalPolicy.disable();
        assertTrue(d.disabled());
        // disabled 时 topN 取常规默认（8），不会影响后续被跳过
    }

    @Test
    void emptyKindsDefaultsToCanonical() {
        RetrievalPolicy p = new RetrievalPolicy(false, null, 0, false, false, "q");
        assertTrue(p.kinds().contains(RetrievalPolicy.RetrievalAssetKind.KNOWLEDGE));
        assertTrue(p.kinds().contains(RetrievalPolicy.RetrievalAssetKind.CASE));
        assertTrue(p.kinds().contains(RetrievalPolicy.RetrievalAssetKind.SKILL));
        assertTrue(p.topN() > 0, "must fall back to a non-zero topN");
    }
}

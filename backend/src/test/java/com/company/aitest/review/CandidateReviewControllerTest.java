package com.company.aitest.review;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import com.company.aitest.minitom.MiniTomService;
import com.company.aitest.trace.TraceCorrectionSuggestionService;
import com.company.aitest.trace.TraceSummaryService;
import com.company.aitest.wiki.WikiService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class CandidateReviewControllerTest {
    private final WikiService wiki = mock(WikiService.class);
    private final AssetReviewInboxQuery inbox = mock(AssetReviewInboxQuery.class);
    private final CandidateReviewController controller = new CandidateReviewController(
            mock(JdbcClient.class), mock(JdbcTemplate.class), mock(TimeProvider.class),
            mock(MiniTomService.class), mock(TraceSummaryService.class),
            mock(TraceCorrectionSuggestionService.class), wiki, inbox);
    private final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            new CurrentUser(1L, "admin", "ADMIN"), null);

    @Test
    void wikiReviewUsesDomainServiceAndBatchDoesNotHideFailure() {
        var batch = new CandidateReviewController.BatchRequest(List.of(
                new CandidateReviewController.CandidateRef("wiki_pack", 7L),
                new CandidateReviewController.CandidateRef("unknown", 8L)));

        assertThrows(BusinessException.class, () -> controller.batchConfirm(batch, auth));
        verify(wiki).reviewPack(7L, "APPROVED", (CurrentUser) auth.getPrincipal());
    }

    @Test
    void rejectsDuplicateBatchTargetsBeforeAnyMutation() {
        var item = new CandidateReviewController.CandidateRef("wiki_entry", 9L);
        assertThrows(BusinessException.class, () -> controller.batchReject(
                new CandidateReviewController.BatchRejectRequest(List.of(item, item), "重复"), auth));
        verifyNoInteractions(wiki);
    }

    @Test
    void automationApprovalRequiresProjectEvidenceReview() {
        assertThrows(BusinessException.class, () -> controller.confirm("automation", 5L, auth));
    }

    @Test
    void staleSkillApprovalReportsConflictInsteadOfClaimingSuccess() {
        assertThrows(BusinessException.class, () -> controller.confirm("skill", 55L, auth));
    }

    @Test
    void pagedInboxKeepsAllCandidatesAndFiltersBeforePaging() {
        var entries = LongStream.rangeClosed(1, 201).mapToObj(id -> Map.<String, Object>of(
                "candidateType", "wiki_pack", "id", id, "name", id == 201 ? "目标知识包" : "普通知识包",
                "createdAt", "2026-11-19 10:00:00")).toList();
        when(inbox.list("wiki_pack", null, null)).thenReturn(entries);

        var first = controller.listCandidatesPage("wiki_pack", null, null, null, null, 1, 50).data();
        var fifth = controller.listCandidatesPage("wiki_pack", null, null, null, null, 5, 50).data();
        var match = controller.listCandidatesPage("wiki_pack", null, null, null, "目标", 1, 50).data();

        org.junit.jupiter.api.Assertions.assertEquals(201, first.total());
        org.junit.jupiter.api.Assertions.assertEquals(50, first.items().size());
        org.junit.jupiter.api.Assertions.assertEquals(1, fifth.items().size());
        org.junit.jupiter.api.Assertions.assertEquals(1, match.total());
        org.junit.jupiter.api.Assertions.assertEquals(201L, match.items().get(0).get("id"));
    }
}

package com.company.aitest.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.company.aitest.common.CurrentUser;
import com.company.aitest.scan.ControlledScanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class TraceAutoScanServiceTest {

    @Mock
    private JdbcClient jdbcClient;
    @Mock
    private ControlledScanService controlledScanService;
    @Mock
    private JdbcClient.StatementSpec listSpec;
    @Mock
    private JdbcClient.MappedQuerySpec<TraceAutoScanService.TraceEventSnapshot> listQuery;

    private TraceAutoScanService service;
    private CurrentUser user;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new TraceAutoScanService(jdbcClient, controlledScanService);
        user = new CurrentUser(9L, "alice", "USER");
    }

    @Test
    void buildDraftsFromEventsShouldProducePageProfiles() {
        List<ControlledScanService.PageProfileDraft> drafts = TraceAutoScanService.buildDraftsFromEvents(List.of(
                new TraceAutoScanService.TraceEventSnapshot(
                        "https://example.com/workflow/request/list",
                        "审批申请 - 平台",
                        "PAGE_OPEN",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                new TraceAutoScanService.TraceEventSnapshot(
                        "https://example.com/workflow/request/list",
                        "审批申请 - 平台",
                        "INPUT",
                        "申请人",
                        "textbox",
                        "张三",
                        "申请信息",
                        null,
                        "申请人"
                ),
                new TraceAutoScanService.TraceEventSnapshot(
                        "https://example.com/workflow/request/list",
                        "审批申请 - 平台",
                        "CLICK",
                        "导出",
                        "button",
                        null,
                        "申请信息",
                        "申请详情",
                        "导出"
                )
        ));

        assertEquals(1, drafts.size());
        ControlledScanService.PageProfileDraft draft = drafts.get(0);
        assertEquals("审批申请", draft.pageLabel());
        assertEquals("/workflow/request/list", draft.routePath());
        assertEquals("审批申请", draft.pageTitle());
        assertEquals(TraceAutoScanService.TRACE_AUTO_SCAN_SOURCE_KEY, draft.sourceKey());
        assertEquals(TraceAutoScanService.TRACE_AUTO_SCAN_SOURCE_LABEL, draft.sourceLabel());
        assertEquals(List.of("审批申请", "申请信息"), draft.headings());
        assertEquals(List.of("申请人", "张三"), draft.fieldLabels());
        assertEquals(List.of("导出"), draft.actionLabels());
        assertEquals(List.of("申请详情"), draft.dialogTitles());
    }

    @SuppressWarnings("unchecked")
    @Test
    void runSessionAutoScanShouldPersistProfilesForStoppedSession() {
        BrowserTraceSessionRecord session = new BrowserTraceSessionRecord(
                12L, 5L, 2L, 9L, 3L, "demo", "chrome", null,
                null, null, "STOPPED", null, null, null, null,
                "Asia/Shanghai", null, null, null, null, null, null
        );

        when(jdbcClient.sql(eq("""
                select page_url, page_title, event_type, element_text, element_role,
                       value_summary, section_title, dialog_title, object_label
                from browser_trace_event
                where trace_session_id = :sessionId
                  and page_url is not null
                  and page_url <> ''
                order by relative_ms asc, id asc
                """))).thenReturn(listSpec);
        when(listSpec.param(eq("sessionId"), eq(12L))).thenReturn(listSpec);
        when(listSpec.query(any(RowMapper.class))).thenReturn(listQuery);
        when(listQuery.list()).thenReturn(List.of(
                new TraceAutoScanService.TraceEventSnapshot(
                        "https://example.com/selfbooking/index",
                        "自助预约 | 门户",
                        "CLICK",
                        "提交预约",
                        "button",
                        null,
                        "预约表单",
                        null,
                        "提交预约"
                )
        ));

        service.runSessionAutoScan(session, user);

        verify(controlledScanService).upsertProfiles(eq(2L), any(List.class), eq(user));
    }
}

package com.company.aitest.scan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ControlledScanServiceTest {

    @Test
    void extractDraftFromHtmlShouldBuildProfileFromUrlPage() {
        String html = """
                <html lang="zh-CN">
                <head>
                  <title>审批申请 - 平台</title>
                </head>
                <body>
                  <nav aria-label="breadcrumb">
                    <a>首页</a>
                    <a>审批中心</a>
                    <span>审批申请</span>
                  </nav>
                  <h1>审批申请</h1>
                  <h2>申请信息</h2>
                  <label>申请人</label>
                  <label>申请单号</label>
                  <input placeholder="请输入部门名称" />
                  <button>查询</button>
                  <button>导出</button>
                  <div class="modal-title">申请详情</div>
                </body>
                </html>
                """;

        ControlledScanService.PageProfileDraft draft = ControlledScanService.extractDraftFromHtml(
                "URL_IMPORT",
                "页面链接扫描",
                "https://example.com/workflow/request/list",
                html);

        assertNotNull(draft);
        assertEquals("审批申请", draft.pageLabel());
        assertEquals("/workflow/request/list", draft.routePath());
        assertEquals("审批中心 / 审批申请", draft.breadcrumbPath());
        assertTrue(draft.headings().contains("审批申请"));
        assertTrue(draft.fieldLabels().contains("申请人"));
        assertTrue(draft.fieldLabels().contains("输入部门名称"));
        assertTrue(draft.actionLabels().contains("查询"));
        assertTrue(draft.actionLabels().contains("导出"));
        assertTrue(draft.dialogTitles().contains("申请详情"));
    }
}

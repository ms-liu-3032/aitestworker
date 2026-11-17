package com.company.aitest.scan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ControlledScanServiceTest {

    @Test
    void extractDraftFromHtmlShouldBuildProfileFromUrlPage() {
        String html = """
                <html lang="zh-CN">
                <head>
                  <title>访客预约 - 威思客平台</title>
                </head>
                <body>
                  <nav aria-label="breadcrumb">
                    <a>首页</a>
                    <a>访客管理</a>
                    <span>访客预约</span>
                  </nav>
                  <h1>访客预约</h1>
                  <h2>预约信息</h2>
                  <label>访客姓名</label>
                  <label>手机号</label>
                  <input placeholder="请输入公司名称" />
                  <button>查询</button>
                  <button>导出</button>
                  <div class="modal-title">预约详情</div>
                </body>
                </html>
                """;

        ControlledScanService.PageProfileDraft draft = ControlledScanService.extractDraftFromHtml(
                "URL_IMPORT",
                "页面链接扫描",
                "https://example.com/vsk/web/visit/visit",
                html);

        assertNotNull(draft);
        assertEquals("访客预约", draft.pageLabel());
        assertEquals("/vsk/web/visit/visit", draft.routePath());
        assertEquals("访客管理 / 访客预约", draft.breadcrumbPath());
        assertTrue(draft.headings().contains("访客预约"));
        assertTrue(draft.fieldLabels().contains("访客姓名"));
        assertTrue(draft.fieldLabels().contains("输入公司名称"));
        assertTrue(draft.actionLabels().contains("查询"));
        assertTrue(draft.actionLabels().contains("导出"));
        assertTrue(draft.dialogTitles().contains("预约详情"));
    }
}

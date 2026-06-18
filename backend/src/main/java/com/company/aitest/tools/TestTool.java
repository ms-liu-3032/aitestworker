package com.company.aitest.tools;

public interface TestTool {
    String toolCode();

    ToolGenerateResponse generate(ToolGenerateRequest request);
}

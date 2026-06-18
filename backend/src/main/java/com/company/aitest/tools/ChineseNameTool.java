package com.company.aitest.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class ChineseNameTool implements TestTool {
    @Override
    public String toolCode() {
        return "chinese-name";
    }

    @Override
    public ToolGenerateResponse generate(ToolGenerateRequest request) {
        List<String> results = new ArrayList<>();
        for (int i = 0; i < request.normalizedCount(); i++) {
            results.add(RandomData.pick(RandomData.FAMILY_NAMES) + RandomData.pick(RandomData.GIVEN_NAMES));
        }
        return new ToolGenerateResponse(toolCode(), results, Map.of("locale", "zh-CN"));
    }
}

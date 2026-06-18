package com.company.aitest.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class FormattedTextTool implements TestTool {
    @Override
    public String toolCode() {
        return "formatted-text";
    }

    @Override
    public ToolGenerateResponse generate(ToolGenerateRequest request) {
        String prefix = request.option("prefix", "TXT");
        List<String> results = new ArrayList<>();
        for (int i = 0; i < request.normalizedCount(); i++) {
            results.add(prefix + "-" + RandomData.between(1000, 9999) + "-" + RandomData.between(100000, 999999));
        }
        return new ToolGenerateResponse(toolCode(), results, Map.of("prefix", prefix));
    }
}

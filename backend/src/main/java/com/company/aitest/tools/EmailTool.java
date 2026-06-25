package com.company.aitest.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class EmailTool implements TestTool {
    @Override
    public String toolCode() {
        return "email";
    }

    @Override
    public ToolGenerateResponse generate(ToolGenerateRequest request) {
        String domain = request.option("domain", "example.com").replace("@", "");
        List<String> results = new ArrayList<>();
        long batchSeed = System.currentTimeMillis();
        for (int i = 0; i < request.normalizedCount(); i++) {
            results.add("test" + batchSeed + String.format("%03d", i) + "@" + domain);
        }
        return new ToolGenerateResponse(toolCode(), results, Map.of("domain", domain));
    }
}

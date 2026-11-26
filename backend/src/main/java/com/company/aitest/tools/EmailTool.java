package com.company.aitest.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

@Component
public class EmailTool implements TestTool {
    private static final AtomicLong SEQUENCE = new AtomicLong(System.currentTimeMillis() * 1000);

    @Override
    public String toolCode() {
        return "email";
    }

    @Override
    public ToolGenerateResponse generate(ToolGenerateRequest request) {
        String domain = request.option("domain", "example.com").replace("@", "");
        List<String> results = new ArrayList<>();
        for (int i = 0; i < request.normalizedCount(); i++) {
            results.add("test" + SEQUENCE.getAndIncrement() + "@" + domain);
        }
        return new ToolGenerateResponse(toolCode(), results, Map.of("domain", domain));
    }
}

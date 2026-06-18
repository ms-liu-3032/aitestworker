package com.company.aitest.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class HmtIdCardTool implements TestTool {
    @Override
    public String toolCode() {
        return "hmt-id-card";
    }

    @Override
    public ToolGenerateResponse generate(ToolGenerateRequest request) {
        String region = request.option("region", "HK").toUpperCase();
        List<String> results = new ArrayList<>();
        for (int i = 0; i < request.normalizedCount(); i++) {
            results.add(switch (region) {
                case "MO" -> "1" + digits(6) + "(" + RandomData.between(0, 9) + ")";
                case "TW" -> "A" + RandomData.between(1, 2) + digits(8);
                default -> "A" + digits(6) + "(" + RandomData.between(0, 9) + ")";
            });
        }
        return new ToolGenerateResponse(toolCode(), results, Map.of("region", region));
    }

    private String digits(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(RandomData.between(0, 9));
        }
        return builder.toString();
    }
}

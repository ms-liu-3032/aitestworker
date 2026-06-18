package com.company.aitest.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class MobilePhoneTool implements TestTool {
    @Override
    public String toolCode() {
        return "mobile-phone";
    }

    @Override
    public ToolGenerateResponse generate(ToolGenerateRequest request) {
        String country = request.option("country", "CN").toUpperCase();
        List<String> results = new ArrayList<>();
        for (int i = 0; i < request.normalizedCount(); i++) {
            results.add(switch (country) {
                case "US" -> "+1" + RandomData.between(200, 999) + RandomData.between(200, 999) + String.format("%04d", RandomData.between(0, 9999));
                case "GB" -> "+447" + String.format("%09d", RandomData.between(0, 999999999));
                case "JP" -> "+8190" + String.format("%08d", RandomData.between(0, 99999999));
                default -> "1" + RandomData.pick(List.of("3", "5", "6", "7", "8", "9")) + String.format("%09d", RandomData.between(0, 999999999));
            });
        }
        return new ToolGenerateResponse(toolCode(), results, Map.of("country", country));
    }
}

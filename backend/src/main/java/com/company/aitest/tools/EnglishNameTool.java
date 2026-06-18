package com.company.aitest.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class EnglishNameTool implements TestTool {
    @Override
    public String toolCode() {
        return "english-name";
    }

    @Override
    public ToolGenerateResponse generate(ToolGenerateRequest request) {
        List<String> results = new ArrayList<>();
        for (int i = 0; i < request.normalizedCount(); i++) {
            results.add(RandomData.pick(RandomData.EN_FIRST_NAMES) + " " + RandomData.pick(RandomData.EN_LAST_NAMES));
        }
        return new ToolGenerateResponse(toolCode(), results, Map.of("locale", "en"));
    }
}

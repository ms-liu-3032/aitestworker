package com.company.aitest.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class AddressTool implements TestTool {
    @Override
    public String toolCode() {
        return "address";
    }

    @Override
    public ToolGenerateResponse generate(ToolGenerateRequest request) {
        List<String> results = new ArrayList<>();
        for (int i = 0; i < request.normalizedCount(); i++) {
            results.add(RandomData.pick(RandomData.CITIES) + RandomData.pick(RandomData.ROADS)
                    + RandomData.between(1, 299) + "号" + RandomData.between(1, 18) + "栋"
                    + RandomData.between(101, 2808));
        }
        return new ToolGenerateResponse(toolCode(), results, Map.of("locale", "zh-CN"));
    }
}

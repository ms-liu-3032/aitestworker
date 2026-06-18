package com.company.aitest.tools;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class MainlandIdCardTool implements TestTool {
    private static final List<String> AREA_CODES = List.of("110105", "310115", "440305", "330106", "510107", "320105");
    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    @Override
    public String toolCode() {
        return "mainland-id-card";
    }

    @Override
    public ToolGenerateResponse generate(ToolGenerateRequest request) {
        List<String> results = new ArrayList<>();
        for (int i = 0; i < request.normalizedCount(); i++) {
            String body = RandomData.pick(AREA_CODES)
                    + RandomData.randomDate(LocalDate.of(1970, 1, 1), LocalDate.of(2005, 12, 31)).format(DateTimeFormatter.BASIC_ISO_DATE)
                    + String.format("%03d", RandomData.between(1, 999));
            results.add(body + checksum(body));
        }
        return new ToolGenerateResponse(toolCode(), results, Map.of("type", "中国大陆居民身份证号"));
    }

    private char checksum(String body) {
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            sum += Character.digit(body.charAt(i), 10) * WEIGHTS[i];
        }
        return CHECK_CODES[sum % 11];
    }
}

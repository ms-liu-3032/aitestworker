package com.company.aitest.tools;

import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MobilePhoneToolTest {

    private static final Pattern CN_PATTERN = Pattern.compile("1[356789]\\d{9}");
    private static final Pattern US_PATTERN = Pattern.compile("\\+1\\d{3}\\d{3}\\d{4}");
    private static final Pattern GB_PATTERN = Pattern.compile("\\+447\\d{9}");
    private static final Pattern JP_PATTERN = Pattern.compile("\\+8190\\d{8}");

    private final MobilePhoneTool tool = new MobilePhoneTool();

    @Test
    void toolCodeIsMobilePhone() {
        assertEquals("mobile-phone", tool.toolCode());
    }

    @Test
    void defaultCountryIsCN() {
        ToolGenerateRequest req = new ToolGenerateRequest(5, null);
        ToolGenerateResponse resp = tool.generate(req);
        assertEquals(5, resp.results().size());
        assertEquals("CN", resp.metadata().get("country"));
        for (String phone : resp.results()) {
            assertTrue(CN_PATTERN.matcher(phone).matches(), "Should match CN format: " + phone);
        }
    }

    @Test
    void cnFormatIs1PlusOperatorDigitPlus9Digits() {
        ToolGenerateRequest req = new ToolGenerateRequest(15, Map.of("country", "CN"));
        ToolGenerateResponse resp = tool.generate(req);
        for (String phone : resp.results()) {
            assertEquals(11, phone.length(), "CN phone should be 11 digits: " + phone);
            assertTrue(CN_PATTERN.matcher(phone).matches(), "CN: " + phone);
        }
    }

    @Test
    void usFormatStartsWithPlus1() {
        ToolGenerateRequest req = new ToolGenerateRequest(10, Map.of("country", "US"));
        ToolGenerateResponse resp = tool.generate(req);
        for (String phone : resp.results()) {
            assertTrue(US_PATTERN.matcher(phone).matches(), "US: " + phone);
        }
    }

    @Test
    void gbFormatStartsWithPlus447() {
        ToolGenerateRequest req = new ToolGenerateRequest(10, Map.of("country", "GB"));
        ToolGenerateResponse resp = tool.generate(req);
        for (String phone : resp.results()) {
            assertTrue(GB_PATTERN.matcher(phone).matches(), "GB: " + phone);
        }
    }

    @Test
    void jpFormatStartsWithPlus8190() {
        ToolGenerateRequest req = new ToolGenerateRequest(10, Map.of("country", "JP"));
        ToolGenerateResponse resp = tool.generate(req);
        for (String phone : resp.results()) {
            assertTrue(JP_PATTERN.matcher(phone).matches(), "JP: " + phone);
        }
    }

    @Test
    void countryIsCaseInsensitive() {
        ToolGenerateRequest req = new ToolGenerateRequest(1, Map.of("country", "us"));
        ToolGenerateResponse resp = tool.generate(req);
        assertEquals("US", resp.metadata().get("country"));
        assertTrue(US_PATTERN.matcher(resp.results().get(0)).matches());
    }

    @Test
    void unknownCountryDefaultsToCNFormat() {
        ToolGenerateRequest req = new ToolGenerateRequest(5, Map.of("country", "XX"));
        ToolGenerateResponse resp = tool.generate(req);
        for (String phone : resp.results()) {
            assertTrue(CN_PATTERN.matcher(phone).matches(), "Unknown country should use CN format: " + phone);
        }
    }

    @Test
    void generatesUniqueNumbers() {
        ToolGenerateRequest req = new ToolGenerateRequest(20, Map.of("country", "CN"));
        ToolGenerateResponse resp = tool.generate(req);
        long distinct = resp.results().stream().distinct().count();
        assertTrue(distinct > 15, "Should generate mostly unique numbers, got " + distinct);
    }
}

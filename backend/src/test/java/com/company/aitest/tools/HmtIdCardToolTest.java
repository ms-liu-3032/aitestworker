package com.company.aitest.tools;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HmtIdCardToolTest {

    private static final Pattern HK_PATTERN = Pattern.compile("A\\d{6}\\(\\d\\)");
    private static final Pattern MO_PATTERN = Pattern.compile("1\\d{6}\\(\\d\\)");
    private static final Pattern TW_PATTERN = Pattern.compile("A[12]\\d{8}");

    private final HmtIdCardTool tool = new HmtIdCardTool();

    @Test
    void toolCodeIsHmtIdCard() {
        assertEquals("hmt-id-card", tool.toolCode());
    }

    @Test
    void defaultRegionIsHK() {
        ToolGenerateRequest req = new ToolGenerateRequest(5, null);
        ToolGenerateResponse resp = tool.generate(req);
        assertEquals(5, resp.results().size());
        assertEquals("HK", resp.metadata().get("region"));
        for (String id : resp.results()) {
            assertTrue(HK_PATTERN.matcher(id).matches(), "Should match HK format: " + id);
        }
    }

    @Test
    void hkFormatIsADigits6ParenDigit() {
        ToolGenerateRequest req = new ToolGenerateRequest(10, Map.of("region", "HK"));
        ToolGenerateResponse resp = tool.generate(req);
        for (String id : resp.results()) {
            assertTrue(HK_PATTERN.matcher(id).matches(), "HK: " + id);
        }
    }

    @Test
    void moFormatIs1Digits6ParenDigit() {
        ToolGenerateRequest req = new ToolGenerateRequest(10, Map.of("region", "MO"));
        ToolGenerateResponse resp = tool.generate(req);
        for (String id : resp.results()) {
            assertTrue(MO_PATTERN.matcher(id).matches(), "MO: " + id);
        }
    }

    @Test
    void twFormatIsALeadDigitPlus8Digits() {
        ToolGenerateRequest req = new ToolGenerateRequest(10, Map.of("region", "TW"));
        ToolGenerateResponse resp = tool.generate(req);
        for (String id : resp.results()) {
            assertTrue(TW_PATTERN.matcher(id).matches(), "TW: " + id);
        }
    }

    @Test
    void regionIsCaseInsensitive() {
        ToolGenerateRequest req = new ToolGenerateRequest(1, Map.of("region", "hk"));
        ToolGenerateResponse resp = tool.generate(req);
        assertEquals("HK", resp.metadata().get("region"));
        assertTrue(HK_PATTERN.matcher(resp.results().get(0)).matches());
    }

    @Test
    void unknownRegionDefaultsToHKFormat() {
        ToolGenerateRequest req = new ToolGenerateRequest(5, Map.of("region", "XX"));
        ToolGenerateResponse resp = tool.generate(req);
        for (String id : resp.results()) {
            assertTrue(HK_PATTERN.matcher(id).matches(), "Unknown region should use HK format: " + id);
        }
    }

    @Test
    void allRegionsGenerateCorrectCount() {
        for (String region : List.of("HK", "MO", "TW")) {
            ToolGenerateRequest req = new ToolGenerateRequest(3, Map.of("region", region));
            ToolGenerateResponse resp = tool.generate(req);
            assertEquals(3, resp.results().size(), "Count mismatch for region " + region);
        }
    }
}

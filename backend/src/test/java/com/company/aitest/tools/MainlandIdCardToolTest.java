package com.company.aitest.tools;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MainlandIdCardToolTest {

    private static final Pattern ID_PATTERN = Pattern.compile("\\d{17}[\\dX]");
    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private final MainlandIdCardTool tool = new MainlandIdCardTool();

    @Test
    void toolCodeIsMainlandIdCard() {
        assertEquals("mainland-id-card", tool.toolCode());
    }

    @Test
    void generates18DigitId() {
        ToolGenerateRequest req = new ToolGenerateRequest(1, null);
        ToolGenerateResponse resp = tool.generate(req);
        assertEquals(1, resp.results().size());
        String id = resp.results().get(0);
        assertEquals(18, id.length());
        assertTrue(ID_PATTERN.matcher(id).matches(), "ID should match 17 digits + digit or X: " + id);
    }

    @Test
    void checksumIsValid() {
        ToolGenerateRequest req = new ToolGenerateRequest(50, null);
        ToolGenerateResponse resp = tool.generate(req);
        for (String id : resp.results()) {
            assertTrue(verifyChecksum(id), "Checksum invalid for: " + id);
        }
    }

    @Test
    void generatesMultipleUniqueIds() {
        ToolGenerateRequest req = new ToolGenerateRequest(20, null);
        ToolGenerateResponse resp = tool.generate(req);
        assertEquals(20, resp.results().size());
        long distinctCount = resp.results().stream().distinct().count();
        assertTrue(distinctCount > 15, "Should generate mostly unique IDs, got " + distinctCount);
    }

    @Test
    void hasCorrectMetadata() {
        ToolGenerateRequest req = new ToolGenerateRequest(1, null);
        ToolGenerateResponse resp = tool.generate(req);
        assertEquals("中国大陆居民身份证号", resp.metadata().get("type"));
    }

    private boolean verifyChecksum(String id) {
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += Character.digit(id.charAt(i), 10) * WEIGHTS[i];
        }
        return id.charAt(17) == CHECK_CODES[sum % 11];
    }
}

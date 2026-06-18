package com.company.aitest.tools;

import java.util.Map;
import java.util.regex.Pattern;
import com.company.aitest.common.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TimestampToolTest {

    private static final Pattern NOW_PATTERN =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} \\| \\d+ \\| \\d+");

    private final TimestampTool tool = new TimestampTool();

    @Test
    void toolCodeIsTimestamp() {
        assertEquals("timestamp", tool.toolCode());
    }

    @Test
    void nowModeReturnsThreePartFormat() {
        ToolGenerateRequest req = new ToolGenerateRequest(1, null);
        ToolGenerateResponse resp = tool.generate(req);
        assertEquals(1, resp.results().size());
        String result = resp.results().get(0);
        assertTrue(NOW_PATTERN.matcher(result).matches(), "Should match datetime | seconds | millis: " + result);
    }

    @Test
    void dateTimeToSeconds() {
        ToolGenerateRequest req = new ToolGenerateRequest(1,
                Map.of("mode", "datetime-to-seconds", "value", "2024-01-01 00:00:00"));
        ToolGenerateResponse resp = tool.generate(req);
        assertEquals("1704038400", resp.results().get(0));
    }

    @Test
    void dateTimeToMillis() {
        ToolGenerateRequest req = new ToolGenerateRequest(1,
                Map.of("mode", "datetime-to-millis", "value", "2024-01-01 00:00:00"));
        ToolGenerateResponse resp = tool.generate(req);
        assertEquals("1704038400000", resp.results().get(0));
    }

    @Test
    void secondsTimestampToDateTime() {
        ToolGenerateRequest req = new ToolGenerateRequest(1,
                Map.of("mode", "timestamp-to-datetime", "value", "1704038400"));
        ToolGenerateResponse resp = tool.generate(req);
        assertEquals("2024-01-01 00:00:00", resp.results().get(0));
    }

    @Test
    void millisTimestampToDateTime() {
        ToolGenerateRequest req = new ToolGenerateRequest(1,
                Map.of("mode", "timestamp-to-datetime", "value", "1704038400000"));
        ToolGenerateResponse resp = tool.generate(req);
        assertEquals("2024-01-01 00:00:00", resp.results().get(0));
    }

    @Test
    void roundTripSecondsToDateTimeAndBack() {
        // seconds -> datetime -> seconds should match
        ToolGenerateRequest toDt = new ToolGenerateRequest(1,
                Map.of("mode", "timestamp-to-datetime", "value", "1704038400"));
        String datetime = tool.generate(toDt).results().get(0);

        ToolGenerateRequest toSec = new ToolGenerateRequest(1,
                Map.of("mode", "datetime-to-seconds", "value", datetime));
        String seconds = tool.generate(toSec).results().get(0);

        assertEquals("1704038400", seconds);
    }

    @Test
    void roundTripMillisToDateTimeAndBack() {
        ToolGenerateRequest toDt = new ToolGenerateRequest(1,
                Map.of("mode", "timestamp-to-datetime", "value", "1704038400000"));
        String datetime = tool.generate(toDt).results().get(0);

        ToolGenerateRequest toMs = new ToolGenerateRequest(1,
                Map.of("mode", "datetime-to-millis", "value", datetime));
        String millis = tool.generate(toMs).results().get(0);

        assertEquals("1704038400000", millis);
    }

    @Test
    void invalidDateTimeFormatThrowsBusinessException() {
        ToolGenerateRequest req = new ToolGenerateRequest(1,
                Map.of("mode", "datetime-to-seconds", "value", "2024/01/01 00:00:00"));
        assertThrows(BusinessException.class, () -> tool.generate(req));
    }

    @Test
    void completelyGarbledDateTimeThrowsBusinessException() {
        ToolGenerateRequest req = new ToolGenerateRequest(1,
                Map.of("mode", "datetime-to-seconds", "value", "not-a-date"));
        assertThrows(BusinessException.class, () -> tool.generate(req));
    }

    @Test
    void timestampWith11DigitsThrowsBusinessException() {
        ToolGenerateRequest req = new ToolGenerateRequest(1,
                Map.of("mode", "timestamp-to-datetime", "value", "12345678901"));
        assertThrows(BusinessException.class, () -> tool.generate(req));
    }

    @Test
    void nonNumericTimestampThrowsBusinessException() {
        ToolGenerateRequest req = new ToolGenerateRequest(1,
                Map.of("mode", "timestamp-to-datetime", "value", "abcdefghij"));
        assertThrows(BusinessException.class, () -> tool.generate(req));
    }

    @Test
    void nowModeUsesDefaultWhenModeNotSpecified() {
        ToolGenerateRequest req = new ToolGenerateRequest(1, null);
        ToolGenerateResponse resp = tool.generate(req);
        assertEquals("now", resp.metadata().get("mode"));
    }

    @Test
    void metadataContainsTimezoneAndMode() {
        ToolGenerateRequest req = new ToolGenerateRequest(1, Map.of("mode", "datetime-to-seconds", "value", "2024-01-01 00:00:00"));
        ToolGenerateResponse resp = tool.generate(req);
        assertEquals("UTC+8", resp.metadata().get("timezone"));
        assertEquals("datetime-to-seconds", resp.metadata().get("mode"));
    }
}

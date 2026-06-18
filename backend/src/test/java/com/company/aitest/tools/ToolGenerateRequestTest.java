package com.company.aitest.tools;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolGenerateRequestTest {

    @Test
    void normalizedCountNullReturns1() {
        ToolGenerateRequest req = new ToolGenerateRequest(null, null);
        assertEquals(1, req.normalizedCount());
    }

    @Test
    void normalizedCountZeroClampedTo1() {
        ToolGenerateRequest req = new ToolGenerateRequest(0, null);
        assertEquals(1, req.normalizedCount());
    }

    @Test
    void normalizedCountNegativeClampedTo1() {
        ToolGenerateRequest req = new ToolGenerateRequest(-5, null);
        assertEquals(1, req.normalizedCount());
    }

    @Test
    void normalizedCountWithinRangeReturnsSame() {
        ToolGenerateRequest req = new ToolGenerateRequest(50, null);
        assertEquals(50, req.normalizedCount());
    }

    @Test
    void normalizedCountAtLowerBound() {
        ToolGenerateRequest req = new ToolGenerateRequest(1, null);
        assertEquals(1, req.normalizedCount());
    }

    @Test
    void normalizedCountAtUpperBound() {
        ToolGenerateRequest req = new ToolGenerateRequest(100, null);
        assertEquals(100, req.normalizedCount());
    }

    @Test
    void normalizedCountExceeds100ClampedTo100() {
        ToolGenerateRequest req = new ToolGenerateRequest(200, null);
        assertEquals(100, req.normalizedCount());
    }

    @Test
    void optionWithNullOptionsReturnsDefault() {
        ToolGenerateRequest req = new ToolGenerateRequest(1, null);
        assertEquals("default", req.option("key", "default"));
    }

    @Test
    void optionWithMissingKeyReturnsDefault() {
        ToolGenerateRequest req = new ToolGenerateRequest(1, Map.of("other", "value"));
        assertEquals("default", req.option("key", "default"));
    }

    @Test
    void optionWithExistingKeyReturnsValue() {
        ToolGenerateRequest req = new ToolGenerateRequest(1, Map.of("key", "myvalue"));
        assertEquals("myvalue", req.option("key", "default"));
    }

    @Test
    void optionWithBlankValueReturnsDefault() {
        ToolGenerateRequest req = new ToolGenerateRequest(1, Map.of("key", "   "));
        assertEquals("default", req.option("key", "default"));
    }
}

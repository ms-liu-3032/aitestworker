package com.company.aitest.tools;

import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmailToolTest {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("test\\d+\\d{3}@.+");

    private final EmailTool tool = new EmailTool();

    @Test
    void toolCodeIsEmail() {
        assertEquals("email", tool.toolCode());
    }

    @Test
    void defaultDomainIsExampleDotCom() {
        ToolGenerateRequest req = new ToolGenerateRequest(5, null);
        ToolGenerateResponse resp = tool.generate(req);
        assertEquals(5, resp.results().size());
        assertEquals("example.com", resp.metadata().get("domain"));
        for (String email : resp.results()) {
            assertTrue(email.endsWith("@example.com"), "Should use default domain: " + email);
        }
    }

    @Test
    void customDomain() {
        ToolGenerateRequest req = new ToolGenerateRequest(3, Map.of("domain", "mycompany.io"));
        ToolGenerateResponse resp = tool.generate(req);
        assertEquals("mycompany.io", resp.metadata().get("domain"));
        for (String email : resp.results()) {
            assertTrue(email.endsWith("@mycompany.io"), "Should use custom domain: " + email);
        }
    }

    @Test
    void stripsAtSignFromDomain() {
        ToolGenerateRequest req = new ToolGenerateRequest(1, Map.of("domain", "@evil.com"));
        ToolGenerateResponse resp = tool.generate(req);
        String email = resp.results().get(0);
        assertEquals("evil.com", resp.metadata().get("domain"));
        assertFalse(email.contains("@@"), "Email should not contain double @: " + email);
        assertEquals(1, email.chars().filter(c -> c == '@').count(), "Email should have exactly one @");
    }

    @Test
    void generatesValidEmailFormat() {
        ToolGenerateRequest req = new ToolGenerateRequest(10, null);
        ToolGenerateResponse resp = tool.generate(req);
        for (String email : resp.results()) {
            assertTrue(EMAIL_PATTERN.matcher(email).matches(), "Invalid format: " + email);
            assertEquals(1, email.chars().filter(c -> c == '@').count(), "Must have exactly one @: " + email);
        }
    }

    @Test
    void generatesUniqueEmails() {
        ToolGenerateRequest req = new ToolGenerateRequest(10, null);
        ToolGenerateResponse resp = tool.generate(req);
        long distinct = resp.results().stream().distinct().count();
        assertEquals(10, distinct, "All emails should be unique due to timestamp");
    }
}

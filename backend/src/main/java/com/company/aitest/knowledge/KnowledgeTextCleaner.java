package com.company.aitest.knowledge;

import java.util.regex.Pattern;

public class KnowledgeTextCleaner {

    private static final Pattern SCRIPT_STYLE = Pattern.compile(
            "<(script|style)[^>]*>.*?</(script|style)>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    public String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String result = SCRIPT_STYLE.matcher(raw).replaceAll("");
        result = BLANK_LINES.matcher(result).replaceAll("\n\n");
        return result.strip();
    }
}

package com.company.aitest.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class KnowledgeTextCleanerTest {

    private final KnowledgeTextCleaner cleaner = new KnowledgeTextCleaner();

    @Test
    void nullInputReturnsEmpty() {
        assertEquals("", cleaner.clean(null));
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertEquals("", cleaner.clean(""));
    }

    @Test
    void mergesMultipleBlankLines() {
        String input = "line1\n\n\n\n\nline2\n\n\nline3";
        String result = cleaner.clean(input);
        assertEquals("line1\n\nline2\n\nline3", result);
    }

    @Test
    void removesScriptTagAndContent() {
        String input = "before\n<script>alert('xss')</script>\nafter";
        String result = cleaner.clean(input);
        assertEquals("before\n\nafter", result);
    }

    @Test
    void removesStyleTagAndContent() {
        String input = "text\n<style>.foo { color: red; }</style>\nmore text";
        String result = cleaner.clean(input);
        assertEquals("text\n\nmore text", result);
    }

    @Test
    void removesMultilineScriptTag() {
        String input = "start\n<script>\nvar x = 1;\nconsole.log(x);\n</script>\nend";
        String result = cleaner.clean(input);
        assertEquals("start\n\nend", result);
    }

    @Test
    void preservesHeaders() {
        String input = "# Heading 1\n\n## Heading 2\n\ncontent";
        String result = cleaner.clean(input);
        assertEquals("# Heading 1\n\n## Heading 2\n\ncontent", result);
    }

    @Test
    void preservesLists() {
        String input = "- item 1\n- item 2\n\n1. first\n2. second";
        String result = cleaner.clean(input);
        assertEquals("- item 1\n- item 2\n\n1. first\n2. second", result);
    }

    @Test
    void preservesTableText() {
        String input = "| col1 | col2 |\n|------|------|\n| a    | b    |";
        String result = cleaner.clean(input);
        assertEquals("| col1 | col2 |\n|------|------|\n| a    | b    |", result);
    }

    @Test
    void stripsLeadingTrailingWhitespace() {
        String input = "  \n\n  content  \n\n  ";
        String result = cleaner.clean(input);
        assertEquals("content", result);
    }

    @Test
    void scriptAndStyleCombo() {
        String input = "<script>x()</script>\n\ntext\n<style>y{}</style>";
        String result = cleaner.clean(input);
        assertEquals("text", result);
    }
}

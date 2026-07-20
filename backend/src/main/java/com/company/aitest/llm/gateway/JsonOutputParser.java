package com.company.aitest.llm.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonOutputParser {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonOutputParser() {
    }

    public static String extractJson(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR, "模型返回为空，无法解析 JSON");
        }
        String text = stripCodeFence(rawOutput.trim());
        for (String candidate : new String[]{text, extractBalanced(text, '[', ']'), extractBalanced(text, '{', '}')}) {
            String normalized = normalize(candidate);
            if (normalized != null) {
                return normalized;
            }
        }
        throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR, "模型返回不是有效 JSON");
    }

    public static JsonNode parseJson(String rawOutput) {
        String json = extractJson(rawOutput);
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new LlmRuntimeException(LlmErrorCode.OUTPUT_PARSE_ERROR, "模型返回 JSON 解析失败");
        }
    }

    public static String normalize(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(OBJECT_MAPPER.readTree(candidate.trim()));
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private static String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int start = text.indexOf('\n');
        int end = text.lastIndexOf("```");
        if (start > 0 && end > start) {
            return text.substring(start + 1, end).trim();
        }
        return text;
    }

    private static String extractBalanced(String text, char open, char close) {
        int start = text.indexOf(open);
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaping) {
                    escaping = false;
                } else if (c == '\\') {
                    escaping = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}

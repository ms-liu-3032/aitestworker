package com.company.aitest.trace;

import java.util.List;
import java.util.Objects;

final class TraceWorkflowSupport {

    private TraceWorkflowSupport() {
    }

    static boolean contains(List<String> candidates, String text) {
        for (String candidate : safeList(candidates)) {
            if (defaultText(candidate, "").equals(defaultText(text, ""))) {
                return true;
            }
        }
        return false;
    }

    static boolean containsAny(String text, List<String> candidates) {
        for (String candidate : safeList(candidates)) {
            if (defaultText(text, "").contains(defaultText(candidate, ""))) {
                return true;
            }
        }
        return false;
    }

    static boolean startsWithAny(String text, List<String> prefixes) {
        for (String prefix : safeList(prefixes)) {
            if (defaultText(text, "").startsWith(defaultText(prefix, ""))) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesAnyRegex(String text, List<String> regexes) {
        for (String regex : safeList(regexes)) {
            if (defaultText(text, "").matches(regex)) {
                return true;
            }
        }
        return false;
    }

    static String rewriteChoice(String desc, List<String> descriptions, List<String> rewrites) {
        List<String> descList = safeList(descriptions);
        List<String> rewriteList = safeList(rewrites);
        for (int i = 0; i < Math.min(descList.size(), rewriteList.size()); i++) {
            if (defaultText(descList.get(i), "").equals(defaultText(desc, ""))) {
                return rewriteList.get(i);
            }
        }
        return null;
    }

    static String extractInputValue(String description, String inputPrefix, String fallback) {
        String desc = defaultText(description, "");
        String normalizedPrefix = defaultText(inputPrefix, "");
        int index = desc.lastIndexOf("”输入");
        int consumedLength = 3;
        if (index < 0) {
            index = desc.lastIndexOf("\"输入");
        }
        if (index < 0) {
            index = normalizedPrefix.isEmpty() ? -1 : desc.lastIndexOf(normalizedPrefix);
            consumedLength = normalizedPrefix.isEmpty() ? 2 : normalizedPrefix.length();
        }
        if (index < 0) {
            index = desc.lastIndexOf("输入");
            consumedLength = 2;
        }
        if (index < 0) {
            return defaultText(fallback, "输入内容");
        }
        int start = index + consumedLength;
        String value = sanitize(desc.substring(Math.min(start, desc.length())));
        return value == null || value.isBlank() ? defaultText(fallback, "输入内容") : value;
    }

    static String selectBestInputValue(String description,
                                       String previous,
                                       String inputPrefix,
                                       List<String> placeholderTokens,
                                       String fallback) {
        if (sanitize(description) == null) {
            return previous;
        }
        String value = extractInputValue(description, inputPrefix, fallback);
        if (value == null) {
            return previous;
        }
        String normalized = sanitize(value);
        if (normalized != null) {
            for (String token : safeList(placeholderTokens)) {
                normalized = normalized.replace(token, "");
            }
            normalized = normalized.replace("”输入", "").replace("\"输入", "").replace("输入", "").trim();
            normalized = normalized.replaceAll("[A-Za-z']+$", "").trim();
            normalized = sanitize(normalized);
        }
        if (normalized == null || normalized.isBlank()
                || contains(placeholderTokens, normalized)
                || defaultText(fallback, "输入内容").equals(normalized)) {
            return previous;
        }
        return normalized;
    }

    static String stripSuffixTokens(String description, List<String> suffixTokens) {
        String desc = defaultText(description, "");
        for (String suffix : safeList(suffixTokens)) {
            String normalizedSuffix = defaultText(suffix, "");
            if (!normalizedSuffix.isEmpty() && desc.contains(normalizedSuffix)) {
                return desc.replace(normalizedSuffix, "");
            }
        }
        return desc;
    }

    static boolean sameStepMeaning(TraceStepNormalizer.CleanTraceStep left, TraceStepNormalizer.CleanTraceStep right) {
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(left.actionType(), right.actionType())
                && Objects.equals(left.description(), right.description())
                && Objects.equals(sanitize(left.pageName()), sanitize(right.pageName()));
    }

    static boolean hasAnyDescriptionAhead(List<TraceStepNormalizer.CleanTraceStep> steps, int start, int distance, List<String> tokens) {
        int end = Math.min(steps.size(), start + distance);
        for (int i = start; i < end; i++) {
            TraceStepNormalizer.CleanTraceStep candidate = steps.get(i);
            String desc = defaultText(candidate.description(), "");
            for (String token : safeList(tokens)) {
                if (desc.contains(defaultText(token, ""))) {
                    return true;
                }
            }
        }
        return false;
    }

    static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    static int defaultInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    static String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.trim().replaceAll("\\s+", " ");
    }

    static String defaultText(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text.trim();
    }
}

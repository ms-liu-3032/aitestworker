package com.company.aitest.llm.gateway.guard;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * LLM 入参/日志快照的基础脱敏。
 * <p>
 * 只处理明显凭证类内容，避免把业务字段（手机号、姓名、证件号等测试数据）过度破坏。
 */
@Component
public class SensitiveDataMasker {
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)bearer\\s+[a-z0-9._\\-+/=]{16,}");
    private static final Pattern OPENAI_STYLE_KEY = Pattern.compile("(?i)\\b(sk|ak)-[a-z0-9_\\-]{16,}");
    private static final Pattern JWT = Pattern.compile("\\beyJ[a-zA-Z0-9_\\-]{10,}\\.[a-zA-Z0-9_\\-]{10,}\\.[a-zA-Z0-9_\\-]{10,}\\b");
    private static final Pattern ASSIGNMENT_SECRET = Pattern.compile(
            "(?i)\\b(api[_-]?key|access[_-]?token|secret|password|passwd|pwd)\\b\\s*[:=]\\s*([^\\s,;，；\\n]{4,})");

    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String masked = BEARER_TOKEN.matcher(text).replaceAll("Bearer [MASKED_TOKEN]");
        masked = OPENAI_STYLE_KEY.matcher(masked).replaceAll("$1-[MASKED_KEY]");
        masked = JWT.matcher(masked).replaceAll("[MASKED_JWT]");
        masked = ASSIGNMENT_SECRET.matcher(masked).replaceAll("$1=[MASKED_SECRET]");
        return masked;
    }
}

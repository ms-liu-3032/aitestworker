package com.company.aitest.tools;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import com.company.aitest.common.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class TimestampTool implements TestTool {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String toolCode() {
        return "timestamp";
    }

    @Override
    public ToolGenerateResponse generate(ToolGenerateRequest request) {
        String mode = request.option("mode", "now");
        String value = request.option("value", "");
        String result = switch (mode) {
            case "datetime-to-seconds" -> String.valueOf(parseDateTime(value).atZone(BEIJING).toEpochSecond());
            case "datetime-to-millis" -> String.valueOf(parseDateTime(value).atZone(BEIJING).toInstant().toEpochMilli());
            case "timestamp-to-datetime" -> FORMATTER.format(fromTimestamp(value));
            default -> {
                Instant now = Instant.now();
                yield FORMATTER.format(LocalDateTime.ofInstant(now, BEIJING)) + " | " + now.getEpochSecond() + " | " + now.toEpochMilli();
            }
        };
        return new ToolGenerateResponse(toolCode(), List.of(result), Map.of("timezone", "UTC+8", "mode", mode));
    }

    private LocalDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value, FORMATTER);
        } catch (RuntimeException exception) {
            throw new BusinessException("时间格式必须为 yyyy-MM-dd HH:mm:ss");
        }
    }

    private LocalDateTime fromTimestamp(String value) {
        if (!value.matches("\\d{10}|\\d{13}")) {
            throw new BusinessException("时间戳必须为 10 位秒级或 13 位毫秒级");
        }
        long raw = Long.parseLong(value);
        Instant instant = value.length() == 10 ? Instant.ofEpochSecond(raw) : Instant.ofEpochMilli(raw);
        return LocalDateTime.ofInstant(instant, BEIJING);
    }
}

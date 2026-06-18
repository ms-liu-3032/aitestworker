package com.company.aitest.tools;

import java.util.List;
import java.util.Map;

public record ToolGenerateResponse(String toolCode, List<String> results, Map<String, String> metadata) {
}

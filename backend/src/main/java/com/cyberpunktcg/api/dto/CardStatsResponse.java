package com.cyberpunktcg.api.dto;

import java.util.Map;

public record CardStatsResponse(
        long total,
        Map<String, Long> byType,
        Map<String, Long> byColor
) {
}

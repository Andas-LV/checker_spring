package com.diplom.checker.features.document.model;

import java.util.Map;

public record DocumentMetrics(
        Map<String, Integer> fontCount,
        Map<Integer, Integer> fontSizeCount,
        StringBuilder fullText,
        int imageCount
) {
}
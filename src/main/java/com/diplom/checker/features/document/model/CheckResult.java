package com.diplom.checker.features.document.model;

import java.util.List;

public record CheckResult(
        String mainFont,
        int mainFontSize,
        int wordCount,
        List<String> grammarMistakes,
        List<String> brokenUrls,
        int imageCount,
        String report) {
}
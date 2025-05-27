package com.diplom.checker.features.document.service;

import com.diplom.checker.features.document.model.CheckResult;
import org.languagetool.JLanguageTool;
import org.languagetool.language.Russian;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class TextAnalysisService {
    private static final Pattern URL_PATTERN = Pattern.compile("\\bhttps?://[^\\s]+", Pattern.CASE_INSENSITIVE);

    private final GeminiAnalysisService geminiAnalysisService;

    public TextAnalysisService(GeminiAnalysisService geminiAnalysisService) {
        this.geminiAnalysisService = geminiAnalysisService;
    }

    public CheckResult analyzeText(Map<String, Integer> fontCount, Map<Integer, Integer> fontSizeCount, StringBuilder fullText, int imageCount) throws IOException {
        String text = fullText.toString();

        return new CheckResult(
                getMainFont(fontCount),
                getMainFontSize(fontSizeCount),
                countWords(text),
                checkGrammar(text),
                checkUrls(text),
                imageCount,
                geminiAnalysisService.analyzeDocument(text)
        );
    }

    private String getMainFont(Map<String, Integer> fontCount) {
        return fontCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");
    }

    private int getMainFontSize(Map<Integer, Integer> fontSizeCount) {
        return fontSizeCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(-1);
    }

    private int countWords(String text) {
        return text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    private List<String> checkGrammar(String text) throws IOException {
        var langTool = new JLanguageTool(new Russian());
        var matches = langTool.check(text);
        var mistakes = new HashSet<String>();
        for (var match : matches) {
            String bad = text.substring(match.getFromPos(), match.getToPos()).trim();
            if (!bad.isEmpty()) {
                mistakes.add(bad);
            }
        }
        return new ArrayList<>(mistakes);
    }

    private List<String> checkUrls(String text) {
        var allLinks = new HashSet<String>();
        var matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            allLinks.add(matcher.group());
        }

        var broken = new ArrayList<String>();
        for (var link : allLinks) {
            try {
                var uri = URI.create(link);
                var connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                if (connection.getResponseCode() >= 400) {
                    broken.add(link);
                }
            } catch (Exception e) {
                broken.add(link);
            }
        }
        return broken;
    }
}
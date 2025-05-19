package com.diplom.checker.controller;

import com.diplom.checker.model.CheckResult;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.languagetool.JLanguageTool;
import org.languagetool.language.Russian;
import org.languagetool.rules.RuleMatch;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class CheckController {

    @PostMapping(value = "/check", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CheckResult checkFile(@RequestParam("file") MultipartFile file) throws Exception {
        Map<String, Integer> fontCount = new HashMap<>();
        Map<Integer, Integer> fontSizeCount = new HashMap<>();
        StringBuilder fullText = new StringBuilder();

        String filename = file.getOriginalFilename();
        if (filename != null && filename.toLowerCase().endsWith(".pdf")) {
            // PDF-ветка
            try (PDDocument pdf = PDDocument.load(file.getInputStream())) {
                PDFTextStripper stripper = new PDFTextStripper() {
                    @Override
                    protected void writeString(String text, List<TextPosition> positions) throws IOException {
                        for (TextPosition pos : positions) {
                            // статистика по шрифтам
                            String fontName = pos.getFont().getName();
                            fontCount.merge(fontName, 1, Integer::sum);
                            // статистика по размеру
                            int sizePt = Math.round(pos.getFontSizeInPt());
                            fontSizeCount.merge(sizePt, 1, Integer::sum);
                        }
                        fullText.append(text).append(" ");
                    }
                };
                stripper.getText(pdf);
            }
        } else {
            // DOCX-ветка
            try (InputStream is = file.getInputStream()) {
                XWPFDocument doc = new XWPFDocument(is);
                for (XWPFParagraph para : doc.getParagraphs()) {
                    for (XWPFRun run : para.getRuns()) {
                        String fontName = run.getFontFamily();
                        if (fontName != null) {
                            fontCount.merge(fontName, 1, Integer::sum);
                        }
                        int size = run.getFontSize();
                        if (size > 0) {
                            fontSizeCount.merge(size, 1, Integer::sum);
                        }
                        String text = run.text();
                        if (text != null) {
                            fullText.append(text).append(" ");
                        }
                    }
                }
            }
        }

        // основной шрифт/размер
        String mainFont = fontCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");
        int mainFontSize = fontSizeCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(-1);

        String text = fullText.toString();

        // 1) словоёб_COUNT
        int wordCount = 0;
        if (!text.isBlank()) {
            wordCount = text.trim().split("\\s+").length;
        }

        // 2) орфография (LanguageTool)
        JLanguageTool langTool = new JLanguageTool(new Russian());
        List<RuleMatch> matches = langTool.check(text);
        Set<String> mistakes = new HashSet<>();
        for (RuleMatch m : matches) {
            String bad = text.substring(m.getFromPos(), m.getToPos()).trim();
            if (!bad.isEmpty()) mistakes.add(bad);
        }

        // 3) рабочие ссылки
        Pattern urlPattern = Pattern.compile("\\bhttps?://[^\\s]+", Pattern.CASE_INSENSITIVE);
        Matcher urlMatcher = urlPattern.matcher(text);
        Set<String> allLinks = new HashSet<>();
        while (urlMatcher.find()) {
            allLinks.add(urlMatcher.group());
        }
        List<String> brokenLinks = new ArrayList<>();
        for (String link : allLinks) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(link).openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 400) {
                    brokenLinks.add(link);
                }
            } catch (Exception e) {
                brokenLinks.add(link);
            }
        }

        return new CheckResult(
                mainFont,
                mainFontSize,
                wordCount,
                new ArrayList<>(mistakes),
                brokenLinks
        );
    }
}

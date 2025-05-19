package com.diplom.checker.controller;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.languagetool.JLanguageTool;
import org.languagetool.language.Russian;
import org.languagetool.rules.RuleMatch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.diplom.checker.model.CheckResult;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class CheckController {

    // Флаг для включения/отключения проверки ссылок
    @Value("${app.checkLinks:false}")
    private boolean checkLinks;

    /**
     * Эхо-эндпоинт: просто возвращает переданное слово.
     */
    @GetMapping("/echo")
    public String echo(@RequestParam("word") String word) {
        return word;
    }

    /**
     * Основной эндпоинт для проверки DOCX и PDF.
     * Возвращает информацию о шрифте, размере, количестве слов, орфографические ошибки и сломанные ссылки.
     */
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
                            String fontName = pos.getFont().getName();
                            fontCount.merge(fontName, 1, Integer::sum);
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

        // Определяем наиболее часто используемые шрифт и размер
        String mainFont = fontCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");
        int mainFontSize = fontSizeCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(-1);

        String text = fullText.toString();

        // Подсчет слов
        int wordCount = text.isBlank() ? 0 : text.trim().split("\\s+").length;

        // Орфография (LanguageTool)
        JLanguageTool langTool = new JLanguageTool(new Russian());
        List<RuleMatch> matches = langTool.check(text);
        Set<String> mistakes = new HashSet<>();
        for (RuleMatch m : matches) {
            String bad = text.substring(m.getFromPos(), m.getToPos()).trim();
            if (!bad.isEmpty()) mistakes.add(bad);
        }

        // Проверка ссылок (опционально)
        List<String> brokenLinks = new ArrayList<>();
        if (checkLinks) {
            Pattern urlPattern = Pattern.compile("\\bhttps?://[^\\s]+", Pattern.CASE_INSENSITIVE);
            Matcher urlMatcher = urlPattern.matcher(text);
            Set<String> allLinks = new HashSet<>();
            while (urlMatcher.find()) {
                allLinks.add(urlMatcher.group());
            }
            // Выполняем запросы в параллели, чтоб не блокировать на долго
            ExecutorService pool = Executors.newFixedThreadPool(10);
            List<CompletableFuture<Optional<String>>> futures = new ArrayList<>();
            for (String link : allLinks) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        HttpURLConnection conn = (HttpURLConnection) new URL(link).openConnection();
                        conn.setRequestMethod("HEAD");
                        conn.setConnectTimeout(1000);
                        conn.setReadTimeout(1000);
                        int code = conn.getResponseCode();
                        return (code < 200 || code >= 400) ? Optional.of(link) : Optional.empty();
                    } catch (Exception e) {
                        return Optional.of(link);
                    }
                }, pool));
            }
            for (CompletableFuture<Optional<String>> future : futures) {
                try {
                    future.orTimeout(2, TimeUnit.SECONDS)
                           .thenAccept(opt -> opt.ifPresent(brokenLinks::add))
                           .get(3, TimeUnit.SECONDS);
                } catch (Exception ignore) {}
            }
            pool.shutdown();
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

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Контроллер проверки документов и эхо-сервис.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class CheckController {
    private static final Logger logger = LoggerFactory.getLogger(CheckController.class);

    // Спец-флаг для включения/отключения проверки ссылок (можно отключить в проде во избежание долгих запросов)
    @Value("${app.checkLinks:true}")
    private boolean checkLinks;

    // Раз инициализируем LanguageTool при старте приложения
    private final JLanguageTool langTool;

    public CheckController() throws IOException {
        this.langTool = new JLanguageTool(new Russian());
        logger.info("LanguageTool initialized with {} rules", langTool.getAllActiveRules().size());
    }

    /**
     * Эхо-эндпоинт.
     */
    @GetMapping("/echo")
    public String echo(@RequestParam("word") String word) {
        return word;
    }

    /**
     * Основной эндпоинт проверки DOCX/PDF.
     */
    @PostMapping(value = "/check", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CheckResult checkFile(@RequestParam("file") MultipartFile file) throws Exception {
        logger.info("Received file '{}' of size {} bytes", file.getOriginalFilename(), file.getSize());

        Map<String, Integer> fontCount = new HashMap<>();
        Map<Integer, Integer> fontSizeCount = new HashMap<>();
        StringBuilder fullText = new StringBuilder();

        String filename = file.getOriginalFilename();
        if (filename != null && filename.toLowerCase().endsWith(".pdf")) {
            // PDF branch
            try (PDDocument pdf = PDDocument.load(file.getInputStream())) {
                PDFTextStripper stripper = new PDFTextStripper() {
                    @Override
                    protected void writeString(String text, List<TextPosition> positions) throws IOException {
                        for (TextPosition pos : positions) {
                            fontCount.merge(pos.getFont().getName(), 1, Integer::sum);
                            fontSizeCount.merge(Math.round(pos.getFontSizeInPt()), 1, Integer::sum);
                        }
                        fullText.append(text).append(' ');
                    }
                };
                stripper.getText(pdf);
            }
        } else {
            // DOCX branch
            try (InputStream is = file.getInputStream()) {
                XWPFDocument doc = new XWPFDocument(is);
                for (XWPFParagraph para : doc.getParagraphs()) {
                    for (XWPFRun run : para.getRuns()) {
                        Optional.ofNullable(run.getFontFamily())
                                .ifPresent(f -> fontCount.merge(f, 1, Integer::sum));
                        if (run.getFontSize() > 0) {
                            fontSizeCount.merge(run.getFontSize(), 1, Integer::sum);
                        }
                        Optional.ofNullable(run.text())
                                .ifPresent(t -> fullText.append(t).append(' '));
                    }
                }
            }
        }

        // Determine main font and size
        String mainFont = fontCount.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse("unknown");
        int mainFontSize = fontSizeCount.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse(-1);

        String text = fullText.toString();
        int wordCount = text.isBlank() ? 0 : text.trim().split("\\s+").length;

        // Spelling check
        List<RuleMatch> matches = langTool.check(text);
        Set<String> mistakes = new HashSet<>();
        for (RuleMatch m : matches) {
            String bad = text.substring(m.getFromPos(), m.getToPos()).trim();
            if (!bad.isEmpty()) mistakes.add(bad);
        }

        // Link checking optionally
        List<String> brokenLinks = new ArrayList<>();
        if (checkLinks) {
            Pattern urlPattern = Pattern.compile("\\bhttps?://[^\\s]+", Pattern.CASE_INSENSITIVE);
            Matcher urlMatcher = urlPattern.matcher(text);
            Set<String> allLinks = new HashSet<>();
            while (urlMatcher.find()) {
                allLinks.add(urlMatcher.group());
            }
            // Parallel HEAD requests with timeouts
            ExecutorService pool = Executors.newFixedThreadPool(8);
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
                    } catch (IOException e) {
                        return Optional.of(link);
                    }
                }, pool));
            }
            for (CompletableFuture<Optional<String>> future : futures) {
                try {
                    Optional<String> res = future.get(2, TimeUnit.SECONDS);
                    res.ifPresent(brokenLinks::add);
                } catch (Exception ignore) {}
            }
            pool.shutdown();
        }

        CheckResult result = new CheckResult(mainFont, mainFontSize, wordCount,
                new ArrayList<>(mistakes), brokenLinks);
        logger.info("Returning CheckResult: font={}, size={}, words={}, mistakes={}, brokenLinks={}",
                mainFont, mainFontSize, wordCount, mistakes.size(), brokenLinks.size());
        return result;
    }
}

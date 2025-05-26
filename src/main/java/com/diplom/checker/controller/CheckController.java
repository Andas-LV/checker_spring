package com.diplom.checker.controller;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URI;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.languagetool.JLanguageTool;
import org.languagetool.language.Russian;
import org.languagetool.rules.RuleMatch;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.diplom.checker.model.CheckResult;

@RestController
@RequestMapping("/api")
public class CheckController {
    @CrossOrigin(origins = {
            "http://localhost:5173",
            "http://localhost:5174",
    })
    @PostMapping(value = "/check", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CheckResult checkFile(@RequestParam("file") MultipartFile file) throws Exception {
        Map<String, Integer> fontCount = new HashMap<>();
        Map<Integer, Integer> fontSizeCount = new HashMap<>();
        StringBuilder fullText = new StringBuilder();
        int imageCount = 0;

        String filename = file.getOriginalFilename();
        if (filename != null && filename.toLowerCase().endsWith(".pdf")) {
            try (PDDocument pdf = PDDocument.load(file.getInputStream())) {
                // счёт изображений
                PDPageTree pages = pdf.getPages();
                for (PDPage page : pages) {
                    PDResources resources = page.getResources();
                    for (var name : resources.getXObjectNames()) {
                        PDXObject xobj = resources.getXObject(name);
                        if (xobj instanceof PDImageXObject) imageCount++;
                    }
                }
                PDFTextStripper stripper = new PDFTextStripper() {
                    @Override
                    protected void writeString(String text, List<TextPosition> positions) throws IOException {
                        for (TextPosition pos : positions) {
                            String fontName = pos.getFont().getName();
                            fontCount.merge(fontName, 1, Integer::sum);
                            int pt = Math.round(pos.getFontSizeInPt());
                            fontSizeCount.merge(pt, 1, Integer::sum);
                        }
                        fullText.append(text).append("\n");
                    }
                };
                stripper.getText(pdf);
            }
        } else {
            try (InputStream is = file.getInputStream()) {
                XWPFDocument doc = new XWPFDocument(is);
                imageCount = doc.getAllPictures().size();
                for (XWPFParagraph para : doc.getParagraphs()) {
                    StringBuilder paraText = new StringBuilder();
                    for (XWPFRun run : para.getRuns()) {
                        String fontName = run.getFontFamily();
                        if (fontName != null) fontCount.merge(fontName, 1, Integer::sum);
                        int size = run.getFontSize();
                        if (size > 0) fontSizeCount.merge(size, 1, Integer::sum);
                        String t = run.text();
                        if (t != null) paraText.append(t);
                    }
                    fullText.append(paraText.toString()).append("\n");
                }
            }
        }

        String mainFont = fontCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");
        int mainFontSize = fontSizeCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(-1);

        String text = fullText.toString();
        int wordCount = text.isBlank() ? 0 : text.trim().split("\\s+").length;

        JLanguageTool langTool = new JLanguageTool(new Russian());
        List<RuleMatch> matches = langTool.check(text);
        Set<String> mistakes = new HashSet<>();
        for (RuleMatch m : matches) {
            String bad = text.substring(m.getFromPos(), m.getToPos()).trim();
            if (!bad.isEmpty()) mistakes.add(bad);
        }

        Pattern urlPat = Pattern.compile("\\bhttps?://[^\\s]+", Pattern.CASE_INSENSITIVE);
        Matcher urlM = urlPat.matcher(text);
        Set<String> allLinks = new HashSet<>();
        while (urlM.find()) allLinks.add(urlM.group());
        List<String> broken = new ArrayList<>();
        for (String l : allLinks) {
            try {
                HttpURLConnection c = (HttpURLConnection) URI.create(l).toURL().openConnection();
                c.setRequestMethod("GET"); c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(2000); c.setReadTimeout(2000);
                if (c.getResponseCode() >= 400) broken.add(l);
            } catch (Exception ex) { broken.add(l);}    }

        Pattern refPat = Pattern.compile(
                "(Список литературы|Использованные литературы|Литература|СПИСОК ИСПОЛЬЗУЕМОЙ ЛИТЕРАТУРЫ)",
                Pattern.CASE_INSENSITIVE
        );
        boolean hasReferences = false;
        Matcher refM = refPat.matcher(text);
        if (refM.find()) {
            String aft = text.substring(refM.end());
            Pattern listPat = Pattern.compile("(?m)^[\\s\\uFEFF\\u00A0]*(?:\\d+\\.|\\[\\d+\\]|[-–•·])\\s+\\S+");
            hasReferences = listPat.matcher(aft).find();
        }

        Pattern capPat = Pattern.compile("(?m)^(?:Рис(?:\\.\\s*|унок\\s*))\\d+[\\.:\\-–—\\s]*(.+)$");
        Matcher capM = capPat.matcher(text);
        List<String> captions = new ArrayList<>();
        while (capM.find()) captions.add(capM.group(1).trim());
        int imagesWithCaption = captions.size();

        return new CheckResult(
                mainFont, mainFontSize, wordCount,
                new ArrayList<>(mistakes), broken, hasReferences,
                imageCount, imagesWithCaption
        );
    }
}
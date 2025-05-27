package com.diplom.checker.features.document.service;

import com.diplom.checker.features.document.model.DocumentMetrics;
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
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DocumentProcessingService {

    public DocumentMetrics processFile(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }

        var fontCount = new HashMap<String, Integer>();
        var fontSizeCount = new HashMap<Integer, Integer>();
        var fullText = new StringBuilder();
        int imageCount = 0;

        String filename = file.getOriginalFilename();
        if (filename != null && filename.toLowerCase().endsWith(".pdf")) {
            imageCount = processPdf(file.getInputStream(), fontCount, fontSizeCount, fullText);
        } else {
            imageCount = processDocx(file.getInputStream(), fontCount, fontSizeCount, fullText);
        }

        return new DocumentMetrics(fontCount, fontSizeCount, fullText, imageCount);
    }

    private int processPdf(InputStream inputStream, Map<String, Integer> fontCount, Map<Integer, Integer> fontSizeCount, StringBuilder fullText) throws IOException {
        try (var pdf = PDDocument.load(inputStream)) {
            int imageCount = 0;
            PDPageTree pages = pdf.getPages();
            for (PDPage page : pages) {
                PDResources resources = page.getResources();
                for (var name : resources.getXObjectNames()) {
                    PDXObject xobj = resources.getXObject(name);
                    if (xobj instanceof PDImageXObject) {
                        imageCount++;
                    }
                }
            }
            var stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    for (var pos : positions) {
                        String fontName = pos.getFont().getName();
                        fontCount.merge(fontName, 1, Integer::sum);
                        int pt = Math.round(pos.getFontSizeInPt());
                        fontSizeCount.merge(pt, 1, Integer::sum);
                    }
                    fullText.append(text).append("\n");
                }
            };
            stripper.getText(pdf);
            return imageCount;
        }
    }

    private int processDocx(InputStream inputStream, Map<String, Integer> fontCount, Map<Integer, Integer> fontSizeCount, StringBuilder fullText) throws IOException {
        try (var doc = new XWPFDocument(inputStream)) {
            int imageCount = doc.getAllPictures().size();
            for (var para : doc.getParagraphs()) {
                var paraText = new StringBuilder();
                for (var run : para.getRuns()) {
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
                        paraText.append(text);
                    }
                }
                fullText.append(paraText).append("\n");
            }
            return imageCount;
        }
    }
}
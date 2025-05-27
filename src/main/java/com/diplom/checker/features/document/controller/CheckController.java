package com.diplom.checker.features.document.controller;

import com.diplom.checker.features.document.model.DocumentMetrics;
import com.diplom.checker.features.document.service.DocumentProcessingService;
import com.diplom.checker.features.document.service.TextAnalysisService;
import com.diplom.checker.features.document.model.CheckResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class CheckController {

    private final DocumentProcessingService documentProcessingService;
    private final TextAnalysisService textAnalysisService;

    public CheckController(DocumentProcessingService documentProcessingService, TextAnalysisService textAnalysisService) {
        this.documentProcessingService = documentProcessingService;
        this.textAnalysisService = textAnalysisService;
    }

    @CrossOrigin(origins = {"*"})
    @PostMapping(value = "/check", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CheckResult checkFile(@RequestParam("file") MultipartFile file) throws Exception {
        DocumentMetrics metrics = documentProcessingService.processFile(file);
        return textAnalysisService.analyzeText(
                metrics.fontCount(),
                metrics.fontSizeCount(),
                metrics.fullText(),
                metrics.imageCount()
        );
    }
}
package com.diplom.checker.features.document.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.Map;
import java.util.List;

@Service
public class GeminiAnalysisService {

    @Value("${gemini.api.url}")
    private String apiUrl;

    private final RestTemplate restTemplate;

    public GeminiAnalysisService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public String analyzeDocument(String text) {
        try {
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            var prompt = """
                    Проанализируй текст дипломной работы и выполни полную проверку на соответствие требованиям:
                    1. Проверь наличие списка литературы (раздел с заголовком "Список литературы", "Литература" и т.д., содержащий нумерованные или маркированные источники).
                    2. Проверь наличие подписей под изображениями (формат: "Рисунок N: Описание" или "Рис. N: Описание").
                    3. Проверь орфографию и грамматику, укажи ошибки.
                    4. Проверь структуру документа: наличие введения, основной части, заключения.
                    5. Оцени логичность и связность текста.
                    6. Проверь форматирование: единообразие шрифтов и размеров, правильные отступы.
                    7. Укажи любые другие недочеты, которые могут повлиять на качество дипломной работы.
                    Верни подробный отчет в текстовом формате, выделяя каждый пункт.
                    Текст документа:
                    %s
                    """.formatted(text);

            var requestBody = Map.of(
                    "contents", Collections.singletonList(
                            Map.of("parts", Collections.singletonList(
                                    Map.of("text", prompt)
                            ))
                    )
            );
            var entity = new HttpEntity<>(requestBody, headers);
            var response = restTemplate.exchange(apiUrl, HttpMethod.POST, entity, Map.class);
            var candidates = (List<Map>) response.getBody().get("candidates");
            var content = (Map) candidates.get(0).get("content");
            var parts = (List<Map>) content.get("parts");
            return (String) parts.get(0).get("text");
        } catch (Exception e) {
            return "Ошибка при анализе документа в Gemini API: " + e.getMessage();
        }
    }
}
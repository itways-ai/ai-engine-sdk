package com.itways.assistant.ai.impl;

import com.itways.assistant.ai.config.AiAgent;
import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class GeminiAgent implements AiAgent {
    private final String defaultApiKey;
    private final RestTemplate restTemplate = new RestTemplate();
    // available models
    //  gemini-2.5-flash-lite fastest

    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={apiKey}";
    private static final String DEFAULT_MODEL = "gemini-2.5-flash-lite";

    @Override
    public String getProvider() {
        return "GEMINI";
    }
    @Override
    public AiResponse chat(AiChatRequest request) {
        String effectiveApiKey = (request.getConfig() != null && request.getConfig().getApiKey() != null)
                ? request.getConfig().getApiKey()
                : defaultApiKey;
        if (effectiveApiKey == null || effectiveApiKey.isEmpty()) {
            return AiResponse.builder().content("Error: Gemini API Key missing").build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String model = request.getModel() != null ? request.getModel() : DEFAULT_MODEL;

        Map<String, Object> body = new HashMap<>();
        body.put("contents", request.getMessages().stream()
                .map(m -> Map.of(
                        "role", m.getRole().equals("assistant") ? "model" : "user",
                        "parts", List.of(Map.of("text", m.getContent()))))
                .collect(Collectors.toList()));

        Map<String, Object> generationConfig = new HashMap<>();
        if (request.getTemperature() != null) {
            generationConfig.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            generationConfig.put("maxOutputTokens", request.getMaxTokens());
        }
        if (!generationConfig.isEmpty()) {
            body.put("generationConfig", generationConfig);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            String url = GEMINI_URL.replace("{model}", model).replace("{apiKey}", effectiveApiKey);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            Map<String, Object> responseBody = response.getBody();
            if (responseBody != null) {
                List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseBody.get("candidates");
                if (candidates != null && !candidates.isEmpty()) {
                    Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
                    List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                    String text = (String) parts.get(0).get("text");

                    Map<String, Object> usageMetadata = (Map<String, Object>) responseBody.get("usageMetadata");
                    AiResponse.Usage usage = null;
                    if (usageMetadata != null) {
                        usage = new AiResponse.Usage(
                                (Integer) usageMetadata.get("promptTokenCount"),
                                (Integer) usageMetadata.get("candidatesTokenCount"),
                                (Integer) usageMetadata.get("totalTokenCount"));
                    }

                    return AiResponse.builder()
                            .content(text)
                            .model(model)
                            .usage(usage)
                            .build();
                }
            }
        } catch (Exception e) {
            return AiResponse.builder().content("Gemini API Error: " + e.getMessage()).build();
        }
        return AiResponse.builder().content("").build();
    }

    @Override
    public AiResponse transcribe(AiTranscriptionRequest request) {
        return AiResponse.builder()
                .content("Error: Gemini does not support audio transcription via this SDK")
                .build();
    }
}

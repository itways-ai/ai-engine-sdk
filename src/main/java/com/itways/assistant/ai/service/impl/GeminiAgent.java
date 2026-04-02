package com.itways.assistant.ai.service.impl;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class GeminiAgent extends AbstractAiAgent {


    public GeminiAgent(String defaultApiKey, RestTemplate restTemplate) {
        super(defaultApiKey, restTemplate);
    }
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
        String model = request.getModel() != null ? request.getModel() : DEFAULT_MODEL;
        log.info("Processing chat request for Gemini, model: {}", model);

        String effectiveApiKey = getEffectiveApiKey(request);
        if (effectiveApiKey.isEmpty()) {
            log.error("Gemini API Key missing");
            return AiResponse.builder().content("Error: Gemini API Key missing").build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

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

                    log.debug("Gemini API call successful, usage: {}", usage);
                    return AiResponse.builder()
                            .content(text)
                            .model(model)
                            .usage(usage)
                            .build();
                }
            }
        } catch (Exception e) {
            log.error("Gemini API Error during chat request", e);
            return AiResponse.builder().content("Gemini API Error: " + e.getMessage()).build();
        }
        log.warn("Gemini API returned an empty or invalid response shape");
        return AiResponse.builder().content("").build();
    }

    @Override
    public AiResponse transcribe(AiTranscriptionRequest request) {
        log.warn("Gemini does not support audio transcription");
        return AiResponse.builder()
                .content("Error: Gemini does not support audio transcription via this SDK")
                .build();
    }
}

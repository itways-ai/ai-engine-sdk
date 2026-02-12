package com.itways.assistant.ai.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.itways.assistant.ai.config.AiAgent;
import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MistralAgent implements AiAgent {

    private final String defaultApiKey;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String MISTRAL_URL = "https://api.mistral.ai/v1/chat/completions";
    private static final String DEFAULT_MODEL = "mistral-large-latest";

    @Override
    public String getProvider() {
        return "MISTRAL";
    }

    @Override
    public AiResponse chat(AiChatRequest request) {
        String effectiveApiKey = (request.getConfig() != null && request.getConfig().getApiKey() != null)
                ? request.getConfig().getApiKey()
                : defaultApiKey;
        if (effectiveApiKey == null || effectiveApiKey.isEmpty()) {
            return AiResponse.builder().content("Error: Mistral API Key missing").build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(effectiveApiKey);

        Map<String, Object> body = new HashMap<>();
        body.put("model", request.getModel() != null ? request.getModel() : DEFAULT_MODEL);
        body.put("messages", request.getMessages().stream()
                .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                .collect(Collectors.toList()));
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(MISTRAL_URL, entity, Map.class);
            Map<String, Object> responseBody = response.getBody();
            if (responseBody != null) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> messageObj = (Map<String, Object>) choices.get(0).get("message");
                    String content = (String) messageObj.get("content");

                    Map<String, Object> usageMap = (Map<String, Object>) responseBody.get("usage");
                    AiResponse.Usage usage = null;
                    if (usageMap != null) {
                        usage = new AiResponse.Usage(
                                (Integer) usageMap.get("prompt_tokens"),
                                (Integer) usageMap.get("completion_tokens"),
                                (Integer) usageMap.get("total_tokens"));
                    }

                    return AiResponse.builder()
                            .content(content)
                            .model((String) responseBody.get("model"))
                            .usage(usage)
                            .build();
                }
            }
        } catch (Exception e) {
            return AiResponse.builder().content("Mistral API Error: " + e.getMessage()).build();
        }
        return AiResponse.builder().content("").build();
    }

    @Override
    public AiResponse transcribe(AiTranscriptionRequest request) {
        return AiResponse.builder()
                .content("Error: Mistral does not support audio transcription")
                .build();
    }
}

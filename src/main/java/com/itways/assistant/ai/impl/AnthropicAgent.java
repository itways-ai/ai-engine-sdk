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
public class AnthropicAgent implements AiAgent {

    private final String defaultApiKey;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_MODEL = "claude-3-5-sonnet-20241022";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Override
    public String getProvider() {
        return "ANTHROPIC";
    }

    @Override
    public AiResponse chat(AiChatRequest request) {
        String effectiveApiKey = (request.getConfig() != null && request.getConfig().getApiKey() != null)
                ? request.getConfig().getApiKey()
                : defaultApiKey;
        if (effectiveApiKey == null || effectiveApiKey.isEmpty()) {
            return AiResponse.builder().content("Error: Anthropic API Key missing").build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", effectiveApiKey);
        headers.set("anthropic-version", ANTHROPIC_VERSION);

        Map<String, Object> body = new HashMap<>();
        body.put("model", request.getModel() != null ? request.getModel() : DEFAULT_MODEL);
        body.put("messages", request.getMessages().stream()
                .map(m -> Map.of("role", m.getRole().equals("system") ? "user" : m.getRole(), "content", m.getContent()))
                .collect(Collectors.toList()));
        body.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : 4096);
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(ANTHROPIC_URL, entity, Map.class);
            Map<String, Object> responseBody = response.getBody();
            if (responseBody != null) {
                List<Map<String, Object>> content = (List<Map<String, Object>>) responseBody.get("content");
                if (content != null && !content.isEmpty()) {
                    String text = (String) content.get(0).get("text");

                    Map<String, Object> usageMap = (Map<String, Object>) responseBody.get("usage");
                    AiResponse.Usage usage = null;
                    if (usageMap != null) {
                        usage = new AiResponse.Usage(
                                (Integer) usageMap.get("input_tokens"),
                                (Integer) usageMap.get("output_tokens"),
                                (Integer) usageMap.get("input_tokens") + (Integer) usageMap.get("output_tokens"));
                    }

                    return AiResponse.builder()
                            .content(text)
                            .model((String) responseBody.get("model"))
                            .usage(usage)
                            .build();
                }
            }
        } catch (Exception e) {
            return AiResponse.builder().content("Anthropic API Error: " + e.getMessage()).build();
        }
        return AiResponse.builder().content("").build();
    }

    @Override
    public AiResponse transcribe(AiTranscriptionRequest request) {
        return AiResponse.builder()
                .content("Error: Anthropic does not support audio transcription")
                .build();
    }
}
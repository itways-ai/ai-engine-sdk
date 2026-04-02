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
public class MistralAgent extends AbstractAiAgent {

    public MistralAgent(String defaultApiKey, org.springframework.web.client.RestTemplate restTemplate) {
        super(defaultApiKey, restTemplate);
    }

    private static final String MISTRAL_URL = "https://api.mistral.ai/v1/chat/completions";

    // available models
    // mistral-small-2506 -> fastest
    // ministral-14b-2512
    // ministral-3b-2512
    private static final String DEFAULT_MODEL = "mistral-small-2506";

    @Override
    public String getProvider() {
        return "MISTRAL";
    }

    @Override
    public AiResponse chat(AiChatRequest request) {
        log.info("Processing chat request for Mistral, model: {}", request.getModel() != null ? request.getModel() : DEFAULT_MODEL);

        String effectiveApiKey = getEffectiveApiKey(request);
        if (effectiveApiKey.isEmpty()) {
            log.error("Mistral API Key missing");
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

                    log.debug("Mistral API call successful, usage: {}", usage);
                    return AiResponse.builder()
                            .content(content)
                            .model((String) responseBody.get("model"))
                            .usage(usage)
                            .build();
                }
            }
        } catch (Exception e) {
            log.error("Mistral API Error during chat request", e);
            return AiResponse.builder().content("Mistral API Error: " + e.getMessage()).build();
        }
        log.warn("Mistral API returned an empty or invalid response shape");
        return AiResponse.builder().content("").build();
    }

    @Override
    public AiResponse transcribe(AiTranscriptionRequest request) {
        log.warn("Mistral does not support audio transcription");
        return AiResponse.builder()
                .content("Error: Mistral does not support audio transcription")
                .build();
    }
}

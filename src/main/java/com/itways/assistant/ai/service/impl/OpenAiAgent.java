package com.itways.assistant.ai.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiEmbeddingRequest;
import com.itways.assistant.ai.dto.AiEmbeddingResponse;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OpenAiAgent extends AbstractAiAgent {

    public OpenAiAgent(String defaultApiKey, RestTemplate restTemplate) {
        super(defaultApiKey, restTemplate);
    }


    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final String OPENAI_SPEECH_URL = "https://api.openai.com/v1/audio/transcriptions";
    private static final String OPENAI_EMBEDDING_URL = "https://api.openai.com/v1/embeddings";
    // available models
    // gpt-5-nano
    // gpt-4o
    private static final String DEFAULT_CHAT_MODEL = "gpt-4o";
    private static final String DEFAULT_WHISPER_MODEL = "whisper-1";
    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small";

    @Override
    public String getProvider() {
        return "OPENAI";
    }

    @Override
    public AiResponse chat(AiChatRequest request) {
        log.info("Processing chat request for OpenAI, model: {}",
                getEffectiveModel(request.getModel(), request, DEFAULT_CHAT_MODEL));
        String effectiveApiKey = getEffectiveApiKey(request);
        if (effectiveApiKey.isEmpty()) {
            log.error("OpenAI API Key missing");
            return AiResponse.builder().content("Error: OpenAI API Key missing").build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(effectiveApiKey);

        Map<String, Object> body = new HashMap<>();
        body.put("model", getEffectiveModel(request.getModel(), request, DEFAULT_CHAT_MODEL));
        body.put("messages", request.getMessages().stream()
                .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                .collect(Collectors.toList()));
        body.put("temperature", request.getTemperature() != null ? request.getTemperature() : 0.7);
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(OPENAI_URL, entity, Map.class);
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

                    log.debug("OpenAI API call successful, usage: {}", usage);
                    return AiResponse.builder()
                            .content(content)
                            .model((String) responseBody.get("model"))
                            .usage(usage)
                            .build();
                }
            }
        } catch (Exception e) {
            log.error("OpenAI API Error during chat request", e);
            return AiResponse.builder().content("OpenAI API Error: " + e.getMessage()).build();
        }
        log.warn("OpenAI API returned an empty or invalid response shape");
        return AiResponse.builder().content("").build();
    }

    @Override
    public AiResponse transcribe(AiTranscriptionRequest request) {
        log.info("Processing transcription request for OpenAI, model: {}",
                request.getModel() != null ? request.getModel() : DEFAULT_WHISPER_MODEL);
        String effectiveApiKey = getEffectiveApiKey(request);
        if (effectiveApiKey.isEmpty()) {
            log.error("OpenAI API Key missing");
            return AiResponse.builder().content("Error: OpenAI API Key missing").build();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.setBearerAuth(effectiveApiKey);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new ByteArrayResource(request.getAudioData()) {
                @Override
                public String getFilename() {
                    return request.getFilename();
                }
            });
            body.add("model", request.getModel() != null ? request.getModel() : DEFAULT_WHISPER_MODEL);

            if (request.getLanguage() != null && !request.getLanguage().isEmpty()
                    && !request.getLanguage().equalsIgnoreCase("auto")) {
                String isoLang = request.getLanguage().contains("-") ? request.getLanguage().split("-")[0]
                        : request.getLanguage();
                body.add("language", isoLang);
            }

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<OpenAIResponse> response = restTemplate.postForEntity(OPENAI_SPEECH_URL, requestEntity,
                    OpenAIResponse.class);
            if (response.getBody() != null) {
                log.debug("OpenAI Transcription successful");
                return AiResponse.builder()
                        .content(response.getBody().getText())
                        .model(request.getModel() != null ? request.getModel() : DEFAULT_WHISPER_MODEL)
                        .build();
            }
            log.warn("No response body from OpenAI Transcription");
            return AiResponse.builder().content("No response from OpenAI").build();

        } catch (Exception e) {
            log.error("Error calling OpenAI Transcription", e);
            return AiResponse.builder().content("Error calling OpenAI: " + e.getMessage()).build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class OpenAIResponse {
        private String text;
    }

//    @Override
//    public AiEmbeddingResponse embed(AiEmbeddingRequest request) {
//        String model = request.getModel() != null ? request.getModel() : DEFAULT_EMBEDDING_MODEL;
//        log.info("Processing embedding request for OpenAI, model: {}", model);
//
//        String effectiveApiKey = getEffectiveApiKey(request);
//        if (effectiveApiKey.isEmpty()) {
//            log.error("OpenAI API Key missing for embedding");
//            throw new IllegalStateException("OpenAI API Key missing");
//        }
//
//        HttpHeaders headers = new HttpHeaders();
//        headers.setContentType(MediaType.APPLICATION_JSON);
//        headers.setBearerAuth(effectiveApiKey);
//
//        Map<String, Object> body = new HashMap<>();
//        body.put("model", model);
//        body.put("input", request.getInput());
//
//        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
//        try {
//            ResponseEntity<Map> response = restTemplate.postForEntity(OPENAI_EMBEDDING_URL, entity, Map.class);
//            Map<String, Object> responseBody = response.getBody();
//            if (responseBody != null) {
//                List<Map<String, Object>> data = (List<Map<String, Object>>) responseBody.get("data");
//                if (data != null && !data.isEmpty()) {
//                    List<Double> rawVector = (List<Double>) data.get(0).get("embedding");
//                    float[] vector = new float[rawVector.size()];
//                    for (int i = 0; i < rawVector.size(); i++) {
//                        vector[i] = rawVector.get(i).floatValue();
//                    }
//                    log.debug("OpenAI embedding successful, dimensions: {}", vector.length);
//                    return AiEmbeddingResponse.builder()
//                            .vector(vector)
//                            .model(model)
//                            .build();
//                }
//            }
//        } catch (Exception e) {
//            log.error("OpenAI Embedding API Error", e);
//            throw new RuntimeException("OpenAI Embedding failed: " + e.getMessage(), e);
//        }
//        throw new RuntimeException("OpenAI Embedding returned empty response");
//    }
}

package com.itways.assistant.ai.service.impl;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiEmbeddingRequest;
import com.itways.assistant.ai.dto.AiEmbeddingResponse;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
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
    private static final String GEMINI_EMBEDDING_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:embedContent?key={apiKey}";
    private static final String DEFAULT_MODEL = "gemini-2.5-flash-lite";
    private static final String DEFAULT_EMBEDDING_MODEL = "gemini-embedding-001";
    private static final String GEMINI_BATCH_EMBED_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:batchEmbedContents?key={apiKey}";

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

    @Override
    public AiEmbeddingResponse embed(AiEmbeddingRequest request) {
        String model = request.getModel() != null ? request.getModel() : DEFAULT_EMBEDDING_MODEL;
        log.info("Processing embedding request for Gemini, model: {}", model);

        String effectiveApiKey = getEffectiveApiKey(request);
        if (effectiveApiKey.isEmpty()) {
            log.error("Gemini API Key missing for embedding");
            throw new IllegalStateException("Gemini API Key missing");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("model", "models/" + model);   // must be prefixed with "models/"
        body.put("content", Map.of("parts", List.of(Map.of("text", request.getInput()))));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            String url = GEMINI_EMBEDDING_URL
                    .replace("{model}", model)
                    .replace("{apiKey}", effectiveApiKey);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            Map<String, Object> responseBody = response.getBody();
            if (responseBody != null) {
                Map<String, Object> embedding = (Map<String, Object>) responseBody.get("embedding");
                if (embedding != null) {
                    List<Double> rawVector = (List<Double>) embedding.get("values");
                    float[] vector = new float[rawVector.size()];
                    for (int i = 0; i < rawVector.size(); i++) {
                        vector[i] = rawVector.get(i).floatValue();
                    }
                    log.debug("Gemini embedding successful, dimensions: {}", vector.length);
                    return AiEmbeddingResponse.builder()
                            .vector(vector)
                            .model(model)
                            .build();
                }
            }
        } catch (Exception e) {
            log.error("Gemini Embedding API Error", e);
            throw new RuntimeException("Gemini Embedding failed: " + e.getMessage(), e);
        }
        throw new RuntimeException("Gemini Embedding returned empty response");
    }

    @Override
    public List<AiEmbeddingResponse> embedBatch(List<AiEmbeddingRequest> requests) {
        if(requests == null || requests.isEmpty()) {
            return List.of();
        }

        // Pull configuration from the first request package instance
        AiEmbeddingRequest firstReq = requests.get(0);
        var aiConfig = firstReq.getConfig();
        String defaultModel = DEFAULT_EMBEDDING_MODEL;
        String effectiveModel = (firstReq.getModel() != null) ? firstReq.getModel() : defaultModel;
        String effectiveApiKey = (aiConfig != null && aiConfig.getApiKey() != null) ? aiConfig.getApiKey() : this.defaultApiKey;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Build the "requests" JSON array matching Gemini standard payload schemas
        List<Map<String, Object>> requestsPayload = requests.stream().map(req ->{
            Map<String, Object> singleRequest = new HashMap<>();
            String currentModel = req.getModel() != null ? req.getModel() : effectiveModel;

            singleRequest.put("model","models/" +currentModel);
            singleRequest.put("content", Map.of("parts", List.of(Map.of("text", req.getInput() != null ? req.getInput().trim() : ""))));
            return singleRequest;
        }).collect(Collectors.toList());

        Map<String, Object> body = Map.of("requests", requestsPayload);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try{
            String url = GEMINI_BATCH_EMBED_URL
                    .replace("{model}", effectiveModel)
                    .replace("{apiKey}", effectiveApiKey);

            log.info("🚀 Requesting batch embeddings from Gemini API for {} items", requests.size());
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            Map<String, Object> responseBody = response.getBody();

            List<AiEmbeddingResponse> results = new ArrayList<>();
            if(responseBody != null &&  responseBody.containsKey("embeddings")) {
                List<Map<String,Object>> embeddingsList = (List<Map<String, Object>>) responseBody.get("embeddings");

                for (Map<String, Object> embedding : embeddingsList) {
                    List<Double> rawVector = (List<Double>) embedding.get("values");
                    float[] vector = new float[rawVector.size()];
                    for (int i = 0; i < rawVector.size(); i++) {
                        vector[i] = rawVector.get(i).floatValue();
                    }

                    results.add(AiEmbeddingResponse.builder()
                                    .vector(vector)
                                    .model(effectiveModel)
                                    .build());
                }
                return results;
            }
        } catch (Exception e) {
            log.error("❌ Gemini API Batch Embedding processing failed", e);
            throw new RuntimeException("Gemini Batch Embedding failed: " + e.getMessage(), e);
        }
        throw new RuntimeException("Gemini Batch Embedding returned an unexpected response structure format");    }
}

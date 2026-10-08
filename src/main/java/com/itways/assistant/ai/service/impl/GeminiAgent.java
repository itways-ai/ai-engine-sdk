package com.itways.assistant.ai.service.impl;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiToolCall;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

@Slf4j
public class GeminiAgent extends AbstractAiAgent {


    public GeminiAgent(String defaultApiKey, RestTemplate restTemplate) {
        super(defaultApiKey, restTemplate);
    }
    // available models
    //  gemini-2.5-flash-lite fastest

    // The key travels in the x-goog-api-key header, not in a ?key= query
    // parameter: a URL ends up in exception messages, and those end up in logs.
    static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent";
    private static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
//    private static final String DEFAULT_EMBEDDING_MODEL = "gemini-embedding-001";
//    private static final String GEMINI_BATCH_EMBED_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:batchEmbedContents?key={apiKey}";
//    private static final String GEMINI_EMBEDDING_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:embedContent?key={apiKey}";

    @Override
    public String getProvider() {
        return "GEMINI";
    }
    @Override
    public AiResponse chat(AiChatRequest request) {
        String model = getEffectiveModel(request.getModel(), request, DEFAULT_MODEL);
        log.info("Processing chat request for Gemini, model: {}", model);

        String effectiveApiKey = getEffectiveApiKey(request);
        if (effectiveApiKey.isEmpty()) {
            return ProviderErrors.missingKey(getProvider(), "Gemini");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", effectiveApiKey);

        Map<String, Object> body = new HashMap<>();
        body.put("contents", request.getMessages().stream()
                .map(GeminiAgent::toContent)
                .collect(Collectors.toList()));
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            List<Map<String, Object>> declarations = new ArrayList<>();
            for (com.itways.assistant.ai.dto.AiTool tool : request.getTools()) {
                Map<String, Object> declaration = new LinkedHashMap<>();
                declaration.put("name", tool.getName());
                declaration.put("description", tool.getDescription());
                declaration.put("parameters", tool.getParameters());
                declarations.add(declaration);
            }
            body.put("tools", List.of(Map.of("functionDeclarations", declarations)));
        }

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
            String url = GEMINI_URL.replace("{model}", model);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            Map<String, Object> responseBody = response.getBody();
            if (responseBody != null) {
                Map<String, Object> usageMetadata = (Map<String, Object>) responseBody.get("usageMetadata");
                AiResponse.Usage usage = null;
                if (usageMetadata != null) {
                    usage = new AiResponse.Usage(
                            (Integer) usageMetadata.get("promptTokenCount"),
                            (Integer) usageMetadata.get("candidatesTokenCount"),
                            (Integer) usageMetadata.get("totalTokenCount"));
                }
                // A prompt Gemini will not answer at all comes back 200 with no
                // candidates and the reason in promptFeedback.
                if (responseBody.get("promptFeedback") instanceof Map<?, ?> feedback
                        && feedback.get("blockReason") != null) {
                    return ProviderErrors.refused(getProvider(), String.valueOf(feedback.get("blockReason")), model,
                            usage);
                }
                List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseBody.get("candidates");
                if (candidates != null && !candidates.isEmpty()) {
                    Object finishReason = candidates.get(0).get("finishReason");
                    Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
                    List<Map<String, Object>> parts = content == null ? null
                            : (List<Map<String, Object>>) content.get("parts");
                    // A reply is a list of parts, and a model that decided to call
                    // something puts a functionCall where the text would be. Reading
                    // only parts[0].text made such a reply look empty.
                    String text = null;
                    List<AiToolCall> toolCalls = new ArrayList<>();
                    for (Map<String, Object> part : parts == null ? List.<Map<String, Object>>of() : parts) {
                        if (part.get("text") instanceof String partText && text == null) {
                            text = partText;
                        }
                        if (part.get("functionCall") instanceof Map<?, ?> call) {
                            String name = call.get("name") == null ? null : String.valueOf(call.get("name"));
                            if (name != null) {
                                // Gemini issues no call id; one is invented so the
                                // conversation has the same shape as everywhere else.
                                toolCalls.add(AiToolCall.of("call_" + toolCalls.size(), name,
                                        OpenAiToolFormat.readArguments(call.get("args"))));
                            }
                        }
                    }

                    // An answer withheld mid-way (SAFETY, RECITATION, …) finishes
                    // with the reason and no parts. Text that did come back stands.
                    if ((text == null || text.isBlank()) && toolCalls.isEmpty()
                            && ProviderErrors.geminiBlocked(finishReason)) {
                        return ProviderErrors.refused(getProvider(), String.valueOf(finishReason), model, usage);
                    }

                    log.debug("Gemini API call successful, usage: {}", usage);
                    return AiResponse.builder()
                            .content(text)
                            .model(model)
                            .toolCalls(toolCalls)
                            .usage(usage)
                            .build();
                }
            }
        } catch (Exception e) {
            return ProviderErrors.fromException(getProvider(), e, effectiveApiKey);
        }
        return ProviderErrors.empty(getProvider());
    }

    /**
     * One message as Gemini wants it. Its vocabulary differs twice over: the
     * assistant is "model", and a tool result is matched to its call by the
     * tool's name rather than by an id.
     */
    private static Map<String, Object> toContent(AiMessage message) {
        if ("tool".equals(message.getRole())) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("name", message.getToolName());
            response.put("response", Map.of("result", message.getContent() == null ? "" : message.getContent()));
            return Map.of("role", "user", "parts", List.of(Map.of("functionResponse", response)));
        }
        String role = "assistant".equals(message.getRole()) ? "model" : "user";
        if (message.hasToolCalls()) {
            List<Map<String, Object>> parts = new ArrayList<>();
            if (message.getContent() != null && !message.getContent().isBlank()) {
                parts.add(Map.of("text", message.getContent()));
            }
            for (AiToolCall call : message.getToolCalls()) {
                parts.add(Map.of("functionCall", Map.of("name", call.getName(),
                        "args", call.getArguments() == null ? Map.of() : call.getArguments())));
            }
            return Map.of("role", role, "parts", parts);
        }
        return Map.of("role", role, "parts", List.of(Map.of("text", message.getContent() == null ? "" : message.getContent())));
    }

    @Override
    public AiResponse transcribe(AiTranscriptionRequest request) {
        return ProviderErrors.unsupported(getProvider(), "audio transcription");
    }

//    @Override
//    public AiEmbeddingResponse embed(AiEmbeddingRequest request) {
//        String model = request.getModel() != null ? request.getModel() : DEFAULT_EMBEDDING_MODEL;
//        log.info("Processing embedding request for Gemini, model: {}", model);
//
//        String effectiveApiKey = getEffectiveApiKey(request);
//        if (effectiveApiKey.isEmpty()) {
//            log.error("Gemini API Key missing for embedding");
//            throw new IllegalStateException("Gemini API Key missing");
//        }
//
//        HttpHeaders headers = new HttpHeaders();
//        headers.setContentType(MediaType.APPLICATION_JSON);
//
//        Map<String, Object> body = new HashMap<>();
//        body.put("model", "models/" + model);   // must be prefixed with "models/"
//        body.put("content", Map.of("parts", List.of(Map.of("text", request.getInput()))));
//
//        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
//        try {
//            String url = GEMINI_EMBEDDING_URL
//                    .replace("{model}", model)
//                    .replace("{apiKey}", effectiveApiKey);
//            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
//            Map<String, Object> responseBody = response.getBody();
//            if (responseBody != null) {
//                Map<String, Object> embedding = (Map<String, Object>) responseBody.get("embedding");
//                if (embedding != null) {
//                    List<Double> rawVector = (List<Double>) embedding.get("values");
//                    float[] vector = new float[rawVector.size()];
//                    for (int i = 0; i < rawVector.size(); i++) {
//                        vector[i] = rawVector.get(i).floatValue();
//                    }
//                    log.debug("Gemini embedding successful, dimensions: {}", vector.length);
//                    return AiEmbeddingResponse.builder()
//                            .vector(vector)
//                            .model(model)
//                            .build();
//                }
//            }
//        } catch (Exception e) {
//            log.error("Gemini Embedding API Error", e);
//            throw new RuntimeException("Gemini Embedding failed: " + e.getMessage(), e);
//        }
//        throw new RuntimeException("Gemini Embedding returned empty response");
//    }

}

package com.itways.assistant.ai.service.impl;

import com.itways.assistant.ai.dto.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * Groq AI Agent implementation. Supports Chat completions (Llama) and Audio
 * transcription (Whisper). Configured with relaxed SSL to handle connection
 * issues in restrictive environments.
 */
@Slf4j
public class GroqAgent extends AbstractAiAgent {

    static final String GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions";
    static final String GROQ_TRANSCRIPTION_URL = "https://api.groq.com/openai/v1/audio/transcriptions";
    // Groq retired the Llama line; both names this agent used to hard-code are gone
    // from the lineup. A default is a last resort — accounts should configure a model —
    // but a dead one turns "no model configured" into an unexplained 404.
    static final String DEFAULT_CHAT_MODEL = "openai/gpt-oss-120b";
    static final String DEFAULT_WHISPER_MODEL = "whisper-large-v3";
    /**
     * The model a request with images is sent to when nobody chose a model.
     * {@code qwen/qwen3.8-27b} is the one vision-capable model on Groq's models
     * and vision pages (checked 2026-09-27; it is listed as a preview model).
     * Groq retired llama-4-scout on 2026-07-17 and qwen3.6-27b on 2026-09-14, so
     * this is a property ({@code ai.groq.vision-model}) rather than a constant:
     * the next retirement is a configuration change, not a release.
     */
    public static final String DEFAULT_VISION_MODEL = "qwen/qwen3.8-27b";

    private final String visionModel;

    public GroqAgent(String defaultApiKey, RestTemplate restTemplate) {
        this(defaultApiKey, restTemplate, DEFAULT_VISION_MODEL);
    }

    public GroqAgent(String defaultApiKey, RestTemplate restTemplate, String visionModel) {
        super(defaultApiKey, restTemplate);
        this.visionModel = visionModel == null || visionModel.isBlank() ? DEFAULT_VISION_MODEL : visionModel;
    }

    @Override
    public String getProvider() {
        return "GROQ";
    }

    @Override
    public AiResponse chat(AiChatRequest request) {
        String model = getEffectiveModel(request.getModel(), request, DEFAULT_CHAT_MODEL);
        log.info("Processing chat request for Groq, model: {}", model);
        String apiKey = getEffectiveApiKey(request);
        if (apiKey.isEmpty()) {
            return ProviderErrors.missingKey(getProvider(), "Groq");
        }

        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(createChatBody(request),
                    createJsonHeaders(apiKey));
            ResponseEntity<Map> response = restTemplate.postForEntity(GROQ_CHAT_URL, entity, Map.class);
            log.debug("Groq chat API call successful");
            return parseChatResponse(response.getBody(), request.getModel());
        } catch (Exception e) {
            return ProviderErrors.fromException(getProvider(), e, apiKey);
        }
    }

    @Override
    public AiResponse transcribe(AiTranscriptionRequest request) {
        String model = getOrDefault(request.getModel(), DEFAULT_WHISPER_MODEL);
        log.info("Processing transcription request for Groq, model: {}", model);
        String apiKey = getEffectiveApiKey(request);
        if (apiKey.isEmpty()) {
            return ProviderErrors.missingKey(getProvider(), "Groq");
        }

        try {
            HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(createTranscriptionBody(request),
                    createMultipartHeaders(apiKey));
            ResponseEntity<GroqResponse> response = restTemplate.postForEntity(GROQ_TRANSCRIPTION_URL, entity,
                    GroqResponse.class);
            log.debug("Groq transcription API call successful");
            return parseTranscriptionResponse(response.getBody(), request.getModel());
        } catch (Exception e) {
            return ProviderErrors.fromException(getProvider(), e, apiKey);
        }
    }

    // -------------------------------------------------------------------------
    // Helper Methods
    // -------------------------------------------------------------------------

    private HttpHeaders createJsonHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        return headers;
    }

    private HttpHeaders createMultipartHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(apiKey);
        return headers;
    }

    private Map<String, Object> createChatBody(AiChatRequest request) {
        Map<String, Object> body = new HashMap<>();

        boolean hasImages = request.getFiles() != null && request.getFiles().stream()
                .anyMatch(f -> f.getMimeType() != null && f.getMimeType().startsWith("image/"));

        String model = getEffectiveModel(request.getModel(), request, DEFAULT_CHAT_MODEL);
        if (hasImages && DEFAULT_CHAT_MODEL.equals(model)) {
            // Only the text default gets swapped for a vision model. A model the
            // caller or the account actually chose is left alone, even with images
            // attached, because overriding a deliberate choice is worse than sending
            // images to a model that will ignore them.
            model = visionModel;
        }

        body.put("model", model);
        body.put("temperature", getOrDefault(request.getTemperature(), 0.1));
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }

        body.put("messages", buildMessages(request));
        List<Map<String, Object>> tools = OpenAiToolFormat.tools(request.getTools());
        if (tools != null) {
            body.put("tools", tools);
        }
        return body;
    }

    private List<Object> buildMessages(AiChatRequest request) {
        List<AiMessage> originalMessages = request.getMessages();
        List<AiWrappedFile> files = request.getFiles();
        boolean hasFiles = files != null && !files.isEmpty();

        List<Object> messages = new ArrayList<>();
        for (int i = 0; i < originalMessages.size(); i++) {
            AiMessage m = originalMessages.get(i);
            boolean isLastUserMessage = (i == originalMessages.size() - 1) && "user".equalsIgnoreCase(m.getRole());

            if (hasFiles && isLastUserMessage) {
                messages.add(Map.of("role", m.getRole(), "content", buildContentWithFiles(m.getContent(), files)));
            } else {
                messages.add(OpenAiToolFormat.message(m));
            }
        }
        return messages;
    }

    private List<Map<String, Object>> buildContentWithFiles(String textContent, List<AiWrappedFile> files) {
        List<Map<String, Object>> contentParts = new ArrayList<>();

        // 1. Add Text
        if (textContent != null && !textContent.isEmpty()) {
            contentParts.add(Map.of("type", "text", "text", textContent));
        }

        // 2. Add Files
        for (AiWrappedFile file : files) {
            String mimeType = file.getMimeType() != null ? file.getMimeType() : "application/octet-stream";

            if (mimeType.startsWith("image/")) {
                // Handle Image (Base64 Data URL)
                String base64Content = Base64.getEncoder().encodeToString(file.getContent());
                String dataUrl = "data:" + mimeType + ";base64," + base64Content;
                contentParts.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
            } else {
                // Handle Other Files: Inject filename as context anyway
                String fileHeader = String.format("\n[Attached File: %s]\n", file.getFilename());

                // Handle Text Files (Direct Content Injection)
                if (mimeType.startsWith("text/") || mimeType.contains("json") || mimeType.contains("xml")
                        || mimeType.contains("yaml") || mimeType.contains("script")
                        || "application/octet-stream".equals(mimeType)) {
                    String fileContent = new String(file.getContent(), StandardCharsets.UTF_8);
                    String injectedText = String.format("%s%s\n", fileHeader, fileContent);
                    contentParts.add(Map.of("type", "text", "text", injectedText));
                } else {
                    // For other binary types (like PDF), AI can't read them directly yet,
                    // but knowing the file is there helps it understand user references.
                    contentParts.add(Map.of("type", "text", "text", fileHeader));
                    log.warn("Skipping content injection for binary file: {} ({})", file.getFilename(), mimeType);
                }
            }
        }
        return contentParts;
    }

    private AiResponse parseChatResponse(Map<?, ?> responseBody, String requestedModel) {
        if (responseBody == null)
            return ProviderErrors.empty(getProvider());

        List<?> choices = (List<?>) responseBody.get("choices");
        if (choices == null || choices.isEmpty())
            return ProviderErrors.empty(getProvider());

        Map<?, ?> firstChoice = (Map<?, ?>) choices.get(0);
        Map<?, ?> message = (Map<?, ?>) firstChoice.get("message");
        AiResponse.Usage usage = parseUsage((Map<?, ?>) responseBody.get("usage"));
        String refusal = ProviderErrors.openAiRefusal(firstChoice, message);
        if (refusal != null) {
            return ProviderErrors.refused(getProvider(), refusal, (String) responseBody.get("model"), usage);
        }
        String content = (String) message.get("content");

        return AiResponse.builder().content(content).model((String) responseBody.get("model"))
                .toolCalls(OpenAiToolFormat.toolCalls(message))
                .usage(usage).build();
    }

    private AiResponse.Usage parseUsage(Map<?, ?> usageMap) {
        if (usageMap == null)
            return null;
        return new AiResponse.Usage((Integer) usageMap.get("prompt_tokens"),
                (Integer) usageMap.get("completion_tokens"), (Integer) usageMap.get("total_tokens"));
    }

    private MultiValueMap<String, Object> createTranscriptionBody(AiTranscriptionRequest request) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(request.getAudioData()) {
            @Override
            public String getFilename() {
                return request.getFilename();
            }
        });
        body.add("model", getOrDefault(request.getModel(), DEFAULT_WHISPER_MODEL));

        if (request.getLanguage() != null && !request.getLanguage().equalsIgnoreCase("auto")) {
            // Extract ISO code (e.g., "en-US" -> "en")
            String isoLang = request.getLanguage().contains("-") ? request.getLanguage().split("-")[0]
                    : request.getLanguage();
            body.add("language", isoLang);
        }
        return body;
    }

    private AiResponse parseTranscriptionResponse(GroqResponse response, String requestedModel) {
        if (response == null)
            return ProviderErrors.empty(getProvider());
        return AiResponse.builder().content(response.getText())
                .model(getOrDefault(requestedModel, DEFAULT_WHISPER_MODEL)).build();
    }

    private <T> T getOrDefault(T value, T defaultValue) {
        return value != null ? value : defaultValue;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class GroqResponse {
        private String text;
    }
}

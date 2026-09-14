package com.itways.assistant.ai.service.impl;

import com.itways.assistant.ai.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class AnthropicAgent extends AbstractAiAgent {

    public AnthropicAgent(String defaultApiKey, org.springframework.web.client.RestTemplate restTemplate) {
        super(defaultApiKey, restTemplate);
    }

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_MODEL = "claude-sonnet-3.5";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Override
    public String getProvider() {
        return "CLAUDE";
    }

    @Override
    public AiResponse chat(AiChatRequest request) {
        log.info("Processing chat request for Claude, model: {}",
                getEffectiveModel(request.getModel(), request, DEFAULT_MODEL));
        String effectiveApiKey = getEffectiveApiKey(request);
        if (effectiveApiKey.isEmpty()) {
            log.error("Claude API Key missing");
            return AiResponse.builder().content("Error: Claude API Key missing").build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", effectiveApiKey);
        headers.set("anthropic-version", ANTHROPIC_VERSION);

        Map<String, Object> body = new HashMap<>();
        body.put("model", getEffectiveModel(request.getModel(), request, DEFAULT_MODEL));
        body.put("messages", request.getMessages().stream()
                .map(AnthropicAgent::toMessage)
                .collect(Collectors.toList()));
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (com.itways.assistant.ai.dto.AiTool tool : request.getTools()) {
                Map<String, Object> declared = new LinkedHashMap<>();
                declared.put("name", tool.getName());
                declared.put("description", tool.getDescription());
                declared.put("input_schema", tool.getParameters());
                tools.add(declared);
            }
            body.put("tools", tools);
        }
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
                    // Content is a list of blocks: text, and tool_use when the model
                    // decided to call something. Reading only the first block's text
                    // made a tool-calling reply look empty.
                    String text = null;
                    List<AiToolCall> toolCalls = new ArrayList<>();
                    for (Map<String, Object> block : content) {
                        if ("tool_use".equals(block.get("type"))) {
                            String id = block.get("id") == null ? "call_" + toolCalls.size()
                                    : String.valueOf(block.get("id"));
                            toolCalls.add(AiToolCall.of(id, String.valueOf(block.get("name")),
                                    OpenAiToolFormat.readArguments(block.get("input"))));
                        } else if (block.get("text") instanceof String blockText && text == null) {
                            text = blockText;
                        }
                    }

                    Map<String, Object> usageMap = (Map<String, Object>) responseBody.get("usage");
                    AiResponse.Usage usage = null;
                    if (usageMap != null) {
                        usage = new AiResponse.Usage(
                                (Integer) usageMap.get("input_tokens"),
                                (Integer) usageMap.get("output_tokens"),
                                (Integer) usageMap.get("input_tokens") + (Integer) usageMap.get("output_tokens"));
                    }

                    log.debug("Claude API call successful, usage: {}", usage);
                    return AiResponse.builder()
                            .content(text)
                            .model((String) responseBody.get("model"))
                            .toolCalls(toolCalls)
                            .usage(usage)
                            .build();
                }
            }
        } catch (Exception e) {
            log.error("Claude API Error during chat request", e);
            return AiResponse.builder().content("Claude API Error: " + e.getMessage()).build();
        }
        log.warn("Claude API returned an empty or invalid response shape");
        return AiResponse.builder().content("").build();
    }

    /**
     * One message as Anthropic wants it. Tool traffic rides inside the content
     * blocks rather than in fields of its own, and a result is sent as a user
     * turn — there is no tool role here.
     */
    private static Map<String, Object> toMessage(AiMessage message) {
        if ("tool".equals(message.getRole())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "tool_result");
            result.put("tool_use_id", message.getToolCallId());
            result.put("content", message.getContent() == null ? "" : message.getContent());
            return Map.of("role", "user", "content", List.of(result));
        }
        String role = "system".equals(message.getRole()) ? "user" : message.getRole();
        if (message.hasToolCalls()) {
            List<Map<String, Object>> blocks = new ArrayList<>();
            if (message.getContent() != null && !message.getContent().isBlank()) {
                blocks.add(Map.of("type", "text", "text", message.getContent()));
            }
            for (AiToolCall call : message.getToolCalls()) {
                Map<String, Object> use = new LinkedHashMap<>();
                use.put("type", "tool_use");
                use.put("id", call.getId());
                use.put("name", call.getName());
                use.put("input", call.getArguments() == null ? Map.of() : call.getArguments());
                blocks.add(use);
            }
            return Map.of("role", role, "content", blocks);
        }
        return Map.of("role", role, "content", message.getContent() == null ? "" : message.getContent());
    }

    @Override
    public AiResponse transcribe(AiTranscriptionRequest request) {
        log.warn("Claude does not support audio transcription");
        return AiResponse.builder()
                .content("Error: Claude does not support audio transcription")
                .build();
    }

}
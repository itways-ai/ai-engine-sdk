package com.itways.assistant.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiTool;
import com.itways.assistant.ai.dto.AiToolCall;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * The tool-calling wire format shared by every provider that speaks OpenAI's
 * chat-completions dialect — OpenAI itself, Groq and Mistral.
 *
 * <p>
 * It lives apart from the agents because the three of them had already drifted:
 * each builds its message list a little differently, and three copies of this
 * would have drifted too. The agents keep their own quirks (images, model
 * substitution) and call in here for the parts that must be identical.
 */
@Slf4j
public final class OpenAiToolFormat {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAiToolFormat() {
    }

    /** {@code tools} as the provider wants it, or null when there are none to declare. */
    public static List<Map<String, Object>> tools(List<AiTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> declared = new ArrayList<>();
        for (AiTool tool : tools) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.getName());
            function.put("description", tool.getDescription());
            function.put("parameters", tool.getParameters() == null ? AiTool.object(Map.of(), List.of())
                    : tool.getParameters());
            declared.add(Map.of("type", "function", "function", function));
        }
        return declared;
    }

    /**
     * One message on the wire.
     *
     * <p>
     * Built with a mutable map rather than {@code Map.of} on purpose: an
     * assistant turn that only asked for tools carries a null content, and
     * {@code Map.of} refuses nulls.
     */
    public static Map<String, Object> message(AiMessage message) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", message.getRole());
        wire.put("content", message.getContent());
        if (message.getToolCallId() != null) {
            wire.put("tool_call_id", message.getToolCallId());
        }
        if (message.hasToolCalls()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (AiToolCall call : message.getToolCalls()) {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", call.getName());
                function.put("arguments", writeArguments(call.getArguments()));
                calls.add(Map.of("id", call.getId(), "type", "function", "function", function));
            }
            wire.put("tool_calls", calls);
        }
        return wire;
    }

    /** The tool calls in a chat-completions reply, or an empty list. */
    @SuppressWarnings("unchecked")
    public static List<AiToolCall> toolCalls(Map<?, ?> assistantMessage) {
        if (assistantMessage == null) {
            return List.of();
        }
        Object raw = assistantMessage.get("tool_calls");
        if (!(raw instanceof List<?> rawCalls) || rawCalls.isEmpty()) {
            return List.of();
        }
        List<AiToolCall> calls = new ArrayList<>();
        for (Object entry : rawCalls) {
            if (!(entry instanceof Map<?, ?> call)) {
                continue;
            }
            Object function = call.get("function");
            if (!(function instanceof Map<?, ?> fn)) {
                continue;
            }
            String id = call.get("id") == null ? "call_" + calls.size() : String.valueOf(call.get("id"));
            String name = fn.get("name") == null ? null : String.valueOf(fn.get("name"));
            if (name == null) {
                continue;
            }
            calls.add(AiToolCall.of(id, name, readArguments(fn.get("arguments"))));
        }
        return calls;
    }

    /**
     * Arguments arrive as a JSON string, and a model can produce a malformed one.
     * A bad parse costs the arguments, never the turn: the call still comes back
     * so the caller can answer it, complaining about what is missing.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> readArguments(Object raw) {
        if (raw instanceof Map<?, ?> already) {
            return (Map<String, Object>) already;
        }
        String json = raw == null ? "" : String.valueOf(raw).trim();
        if (json.isEmpty() || "null".equals(json)) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Could not read tool arguments, treating them as absent: {}", json);
            return Map.of();
        }
    }

    static String writeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return MAPPER.writeValueAsString(arguments);
        } catch (Exception e) {
            log.warn("Could not write tool arguments back: {}", e.getMessage());
            return "{}";
        }
    }
}

package com.itways.assistant.ai.service.impl;

import com.itways.assistant.ai.dto.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * Claude, over the Messages API ({@code POST /v1/messages}).
 *
 * <p>
 * Current Claude models tightened the request surface, and this agent follows
 * the strictest of them so the default works everywhere:
 * <ul>
 * <li>Sampling parameters ({@code temperature}, {@code top_p}, {@code top_k})
 * are rejected with a 400 by Claude Opus 5, Opus 4.7/4.8, Sonnet 5 and Fable.
 * They are sent only to the older ids known to accept them
 * ({@link #acceptsSamplingParameters}); for everything else they are dropped.
 * <li>A conversation that ends on an assistant turn (a "prefill") is rejected
 * by every current model, so a trailing plain assistant turn is not sent.
 * <li>System text goes in the top-level {@code system} field, which is where
 * the API reads instructions from; it used to be sent as a user turn.
 * </ul>
 */
@Slf4j
public class AnthropicAgent extends AbstractAiAgent {

    static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    /** The current recommended Claude model. Accounts override it with their own model. */
    public static final String DEFAULT_MODEL = "claude-opus-5";
    static final String ANTHROPIC_VERSION = "2023-06-01";
    /**
     * Output cap when the caller sets none. Current models think by default and
     * thinking counts against {@code max_tokens}, so the old 4096 could cut an
     * answer off; 16000 is the documented default for non-streaming calls (large
     * enough for thinking plus the answer, small enough to stay under HTTP
     * timeouts). Overridable with {@code ai.anthropic.default-max-tokens}.
     */
    public static final int DEFAULT_MAX_TOKENS = 16000;
    /** Beta header for {@code "fallbacks": "default"} (server-side refusal fallback). */
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    /**
     * What a declined request used to come back as, in {@code content}.
     *
     * @deprecated a refusal is now {@code AiResponse.getError()} with kind
     *             {@code REFUSED} and the category on the error; {@code content}
     *             is null. Nothing produces this text any more.
     */
    @Deprecated(since = "1.1.0", forRemoval = true)
    public static final String REFUSAL_PREFIX = "Claude API Error: refusal";

    /**
     * Ids that still accept sampling parameters: the Claude 2 / 3 / 3.5 / 3.7
     * lines and Claude 4.0-4.6 (with or without a date suffix). Anything newer
     * or unknown is treated as rejecting them, because omitting them is always
     * valid and sending them is a 400 on every current model.
     */
    private static final Pattern SAMPLING_ALLOWED = Pattern.compile(
            "^claude-(instant|2|3).*"
                    + "|^claude-(opus|sonnet|haiku)-4(-[0-6])?(-\\d{8})?$");

    /**
     * Models whose safety classifiers can decline a request with
     * {@code stop_reason: "refusal"}. For these the request opts into the API's
     * server-side fallback, which reruns a declined request on Anthropic's
     * recommended substitute instead of returning an empty answer.
     */
    private static final Pattern SERVER_FALLBACK_MODELS = Pattern.compile("^claude-(opus|fable|mythos)-5.*");

    /** Models already reported as dropping sampling parameters, so the note is logged once each. */
    private final Set<String> samplingNoted = ConcurrentHashMap.newKeySet();

    private final String defaultModel;
    private final boolean serverSideFallback;
    private final int defaultMaxTokens;

    public AnthropicAgent(String defaultApiKey, RestTemplate restTemplate) {
        this(defaultApiKey, restTemplate, DEFAULT_MODEL, true, DEFAULT_MAX_TOKENS);
    }

    public AnthropicAgent(String defaultApiKey, RestTemplate restTemplate, String defaultModel,
            boolean serverSideFallback) {
        this(defaultApiKey, restTemplate, defaultModel, serverSideFallback, DEFAULT_MAX_TOKENS);
    }

    public AnthropicAgent(String defaultApiKey, RestTemplate restTemplate, String defaultModel,
            boolean serverSideFallback, int defaultMaxTokens) {
        super(defaultApiKey, restTemplate);
        this.defaultModel = defaultModel == null || defaultModel.isBlank() ? DEFAULT_MODEL : defaultModel;
        this.serverSideFallback = serverSideFallback;
        this.defaultMaxTokens = defaultMaxTokens > 0 ? defaultMaxTokens : DEFAULT_MAX_TOKENS;
    }

    @Override
    public String getProvider() {
        return "CLAUDE";
    }

    /** True when {@code model} accepts {@code temperature}/{@code top_p}/{@code top_k}. */
    static boolean acceptsSamplingParameters(String model) {
        return model != null && SAMPLING_ALLOWED.matcher(model).matches();
    }

    static boolean usesServerSideFallback(String model) {
        return model != null && SERVER_FALLBACK_MODELS.matcher(model).matches();
    }

    @Override
    public AiResponse chat(AiChatRequest request) {
        String model = getEffectiveModel(request.getModel(), request, defaultModel);
        log.info("Processing chat request for Claude, model: {}", model);
        String effectiveApiKey = getEffectiveApiKey(request);
        if (effectiveApiKey.isEmpty()) {
            return ProviderErrors.missingKey(getProvider(), "Claude");
        }

        boolean fallback = serverSideFallback && usesServerSideFallback(model);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", effectiveApiKey);
        headers.set("anthropic-version", ANTHROPIC_VERSION);
        if (fallback) {
            // The fallbacks field is only accepted together with this beta header;
            // both are set from the same flag so one never goes without the other.
            headers.set("anthropic-beta", FALLBACK_BETA);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : defaultMaxTokens);
        List<AiMessage> messages = request.getMessages() == null ? List.of() : request.getMessages();
        int leadingSystem = leadingSystemCount(messages);
        String system = systemText(request.getSystemPrompt(), messages.subList(0, leadingSystem));
        if (system != null) {
            body.put("system", system);
        }
        body.put("messages", buildMessages(messages, leadingSystem));
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (AiTool tool : request.getTools()) {
                Map<String, Object> declared = new LinkedHashMap<>();
                declared.put("name", tool.getName());
                declared.put("description", tool.getDescription());
                declared.put("input_schema", tool.getParameters() == null ? AiTool.object(Map.of(), List.of())
                        : tool.getParameters());
                tools.add(declared);
            }
            body.put("tools", tools);
        }
        if (request.getTemperature() != null) {
            if (acceptsSamplingParameters(model)) {
                body.put("temperature", request.getTemperature());
            } else if (samplingNoted.add(model)) {
                log.debug("Not sending temperature to {}: current Claude models reject sampling parameters", model);
            }
        }
        if (fallback) {
            body.put("fallbacks", "default");
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(ANTHROPIC_URL, entity, Map.class);
            Map<String, Object> responseBody = response.getBody();
            if (responseBody != null) {
                Object stopReason = responseBody.get("stop_reason");
                if ("refusal".equals(stopReason)) {
                    // A decline is a normal 200 with no usable answer (after any
                    // server-side fallback also declined). It is an error, not an
                    // empty answer: REFUSED, with the classifier's category.
                    Object details = responseBody.get("stop_details");
                    Object category = details instanceof Map<?, ?> d ? d.get("category") : null;
                    return ProviderErrors.refused(getProvider(), category == null ? null : String.valueOf(category),
                            (String) responseBody.get("model"),
                            parseUsage((Map<String, Object>) responseBody.get("usage")));
                }
                if ("max_tokens".equals(stopReason)) {
                    log.warn("Claude reply was cut off at max_tokens={} (model {}); raise maxTokens or "
                            + "ai.anthropic.default-max-tokens", body.get("max_tokens"), responseBody.get("model"));
                }
                List<Map<String, Object>> content = (List<Map<String, Object>>) responseBody.get("content");
                if (content != null && !content.isEmpty()) {
                    // Content is a list of blocks: text, tool_use when the model
                    // decided to call something, and thinking / fallback markers
                    // that carry no answer. Reading only the first block's text made
                    // a tool-calling or thinking reply look empty.
                    String text = null;
                    List<AiToolCall> toolCalls = new ArrayList<>();
                    for (Map<String, Object> block : content) {
                        if ("tool_use".equals(block.get("type"))) {
                            String id = block.get("id") == null ? "call_" + toolCalls.size()
                                    : String.valueOf(block.get("id"));
                            toolCalls.add(AiToolCall.of(id, String.valueOf(block.get("name")),
                                    OpenAiToolFormat.readArguments(block.get("input"))));
                        } else if ("text".equals(block.get("type")) && block.get("text") instanceof String blockText
                                && text == null) {
                            text = blockText;
                        }
                    }

                    AiResponse.Usage usage = parseUsage((Map<String, Object>) responseBody.get("usage"));
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
            return ProviderErrors.fromException(getProvider(), e, effectiveApiKey);
        }
        return ProviderErrors.empty(getProvider());
    }

    private static AiResponse.Usage parseUsage(Map<String, Object> usageMap) {
        if (usageMap == null) {
            return null;
        }
        Integer in = usageMap.get("input_tokens") instanceof Number n ? n.intValue() : null;
        Integer out = usageMap.get("output_tokens") instanceof Number n ? n.intValue() : null;
        Integer total = in == null || out == null ? null : in + out;
        return new AiResponse.Usage(in, out, total);
    }

    /**
     * How many system messages open the conversation. Those move to the
     * top-level {@code system} field. When the conversation is nothing but
     * system messages they stay put (sent as user turns, as before), because a
     * request with no messages at all is rejected.
     */
    private static int leadingSystemCount(List<AiMessage> messages) {
        int count = 0;
        while (count < messages.size() && "system".equals(messages.get(count).getRole())) {
            count++;
        }
        return count == messages.size() ? 0 : count;
    }

    /**
     * The request's system prompt plus the system messages that open the
     * conversation, joined, or null when there is none. A system message that
     * appears later stays where it is (sent as a user turn) so its position
     * still means something.
     */
    private static String systemText(String systemPrompt, List<AiMessage> leadingSystem) {
        List<String> parts = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            parts.add(systemPrompt);
        }
        for (AiMessage message : leadingSystem) {
            if (message.getContent() != null && !message.getContent().isBlank()) {
                parts.add(message.getContent());
            }
        }
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    private static List<Map<String, Object>> buildMessages(List<AiMessage> source, int start) {
        int end = source.size();
        // A plain assistant turn at the end is a prefill, which current models
        // reject with a 400. An assistant turn that asked for tools is never last
        // in a valid exchange (its results follow it), so only text turns go.
        while (end > start && "assistant".equals(source.get(end - 1).getRole())
                && !source.get(end - 1).hasToolCalls()) {
            log.warn("Dropping a trailing assistant message: Claude does not accept assistant prefill");
            end--;
        }
        List<Map<String, Object>> wire = new ArrayList<>();
        for (AiMessage message : source.subList(start, end)) {
            wire.add(toMessage(message));
        }
        return wire;
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
        return ProviderErrors.unsupported(getProvider(), "audio transcription");
    }

}

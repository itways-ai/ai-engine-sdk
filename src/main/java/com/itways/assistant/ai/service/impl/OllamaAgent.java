package com.itways.assistant.ai.service.impl;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;
import com.itways.assistant.ai.dto.AiWrappedFile;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Chat through a self-hosted Ollama, in its OpenAI-compatible dialect
 * ({@code POST {base-url}/v1/chat/completions}), tool calls included.
 *
 * <p>
 * Unlike the hosted providers there is no key: nothing is sent in
 * {@code Authorization}, whatever the request's config carries. The address is
 * the deployment's ({@code ai.ollama.base-url}), not a constant, and the agent
 * exists only when it is set.
 *
 * <p>
 * A local model on a laptop answers far more slowly than a hosted one, and
 * the first call after a while also loads the model, so the agent has its own
 * HTTP client with its own read timeout ({@code ai.ollama.read-timeout},
 * {@value #DEFAULT_READ_TIMEOUT_SECONDS} s by default) instead of the shared
 * {@code aiRestTemplate}.
 *
 * <p>
 * Thinking models (Qwen3) think before they answer unless told not to; on this
 * endpoint only {@code "reasoning_effort":"none"} switches it off
 * ({@code "think":false} is ignored there). The agent sends
 * {@code ai.ollama.reasoning-effort} ({@value #DEFAULT_REASONING_EFFORT} by
 * default; blank sends nothing), and strips any {@code <think>…</think>} block
 * that still comes back in the content.
 *
 * <p>
 * Audio transcription and images are answered "unsupported" (an
 * {@link com.itways.assistant.ai.dto.AiError.Kind#BAD_REQUEST} error), without
 * a call.
 */
@Slf4j
public class OllamaAgent extends AbstractAiAgent {

    public static final String PROVIDER = "OLLAMA";
    /**
     * The model used when neither the request nor the account named one. Qwen3 8B
     * with thinking off: Qwen2.5 7B drifted into other languages mid-answer on
     * Arabic questions.
     */
    public static final String DEFAULT_MODEL = "qwen3:8b";
    /** {@code reasoning_effort} sent when the deployment sets none: thinking off. */
    public static final String DEFAULT_REASONING_EFFORT = "none";
    public static final long DEFAULT_READ_TIMEOUT_SECONDS = 120;
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(DEFAULT_READ_TIMEOUT_SECONDS);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** A model's thinking, closed; with the whitespace after it. */
    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think>.*?</think>\\s*");

    private final String chatUrl;
    private final String defaultModel;
    /** Sent as {@code reasoning_effort}; null sends nothing. */
    private final String reasoningEffort;

    /**
     * With {@link #DEFAULT_REASONING_EFFORT}.
     *
     * @see #OllamaAgent(String, String, String, RestTemplate)
     */
    public OllamaAgent(String baseUrl, String defaultModel, RestTemplate restTemplate) {
        this(baseUrl, defaultModel, DEFAULT_REASONING_EFFORT, restTemplate);
    }

    /**
     * @param baseUrl         Ollama's address, e.g. {@code http://host.docker.internal:11434};
     *                        a trailing slash or {@code /v1} is accepted
     * @param defaultModel    the model when neither the request nor the account
     *                        names one; blank means {@link #DEFAULT_MODEL}
     * @param reasoningEffort sent as {@code reasoning_effort} on every call
     *                        ({@code none} switches a thinking model's thinking
     *                        off); null or blank sends nothing
     * @param restTemplate    the client; {@link #restTemplate(Duration)} builds the
     *                        one the auto-configuration uses
     */
    public OllamaAgent(String baseUrl, String defaultModel, String reasoningEffort, RestTemplate restTemplate) {
        super(null, restTemplate);
        this.chatUrl = chatUrl(baseUrl);
        this.defaultModel = defaultModel == null || defaultModel.isBlank() ? DEFAULT_MODEL : defaultModel.strip();
        this.reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank() ? null : reasoningEffort.strip();
    }

    /** {@code {base}/v1/chat/completions}, whether or not the base already ends in /v1. */
    static String chatUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("ai.ollama.base-url is required for the Ollama agent");
        }
        String base = baseUrl.strip().replaceAll("/+$", "");
        if (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - 3);
        }
        return base + "/v1/chat/completions";
    }

    /**
     * A pooled plain-HTTP client: connect {@link #CONNECT_TIMEOUT}, and
     * {@code readTimeout} both for the socket and for the whole response.
     */
    public static RestTemplate restTemplate(Duration readTimeout) {
        if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("ai.ollama.read-timeout must be positive, was " + readTimeout);
        }
        Timeout read = Timeout.ofMilliseconds(readTimeout.toMillis());
        PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT.toMillis()))
                        .setSocketTimeout(read)
                        .build())
                // One local server: it queues what it cannot run in parallel anyway.
                .setMaxConnTotal(10)
                .setMaxConnPerRoute(10)
                .build();
        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connections)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(10))
                        .setResponseTimeout(read)
                        .build())
                .evictIdleConnections(Timeout.ofMinutes(5))
                .evictExpiredConnections()
                .build();
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(client));
    }

    @Override
    public String getProvider() {
        return PROVIDER;
    }

    /** Where chat requests go. */
    public String chatUrl() {
        return chatUrl;
    }

    /** What is sent as {@code reasoning_effort}; null when nothing is. */
    public String reasoningEffort() {
        return reasoningEffort;
    }

    @Override
    public AiResponse chat(AiChatRequest request) {
        String model = getEffectiveModel(request.getModel(), request, defaultModel);
        log.info("Processing chat request for Ollama, model: {}", model);
        if (hasImages(request.getFiles())) {
            return ProviderErrors.unsupported(getProvider(), "images");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // No Authorization: a local Ollama has no key, and one set on the account
        // must not be sent to wherever this address points.

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", request.getMessages().stream()
                .map(OllamaAgent::message)
                .collect(Collectors.toList()));
        List<Map<String, Object>> tools = OpenAiToolFormat.tools(request.getTools());
        if (tools != null) {
            body.put("tools", tools);
        }
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }
        if (reasoningEffort != null) {
            body.put("reasoning_effort", reasoningEffort);
        }

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(chatUrl, new HttpEntity<>(body, headers),
                    Map.class);
            return parse(response.getBody());
        } catch (Exception e) {
            return ProviderErrors.fromException(getProvider(), e, null);
        }
    }

    @SuppressWarnings("unchecked")
    private AiResponse parse(Map<String, Object> responseBody) {
        if (responseBody == null) {
            return ProviderErrors.empty(getProvider());
        }
        if (!(responseBody.get("choices") instanceof List<?> choices) || choices.isEmpty()
                || !(choices.get(0) instanceof Map<?, ?> choice)) {
            return ProviderErrors.empty(getProvider());
        }
        Map<?, ?> message = choice.get("message") instanceof Map<?, ?> m ? m : null;
        AiResponse.Usage usage = usage(responseBody.get("usage"));
        String model = responseBody.get("model") instanceof String s ? s : null;
        String refusal = ProviderErrors.openAiRefusal(choice, message);
        if (refusal != null) {
            return ProviderErrors.refused(getProvider(), refusal, model, usage);
        }
        if (message == null) {
            return ProviderErrors.empty(getProvider());
        }
        log.debug("Ollama call successful, usage: {}", usage);
        return AiResponse.builder()
                .content(message.get("content") instanceof String content ? withoutThinking(content) : null)
                .model(model)
                .toolCalls(OpenAiToolFormat.toolCalls(message))
                .usage(usage)
                .build();
    }

    /**
     * {@code content} without any {@code <think>…</think>} block, in case a model
     * thinks although asked not to; unchanged when there is none.
     */
    static String withoutThinking(String content) {
        String answer = THINK_BLOCK.matcher(content).replaceAll("");
        return answer.length() == content.length() ? content : answer.strip();
    }

    /**
     * One message on the wire. Ollama accepts a null {@code content} only on a
     * turn that asked for tools and rejects the request otherwise, so null is
     * always sent as {@code ""}.
     */
    private static Map<String, Object> message(AiMessage message) {
        Map<String, Object> wire = OpenAiToolFormat.message(message);
        if (wire.get("content") == null) {
            wire.put("content", "");
        }
        return wire;
    }

    private static AiResponse.Usage usage(Object raw) {
        if (!(raw instanceof Map<?, ?> usage)) {
            return null;
        }
        return new AiResponse.Usage(integer(usage.get("prompt_tokens")), integer(usage.get("completion_tokens")),
                integer(usage.get("total_tokens")));
    }

    private static Integer integer(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static boolean hasImages(List<AiWrappedFile> files) {
        return files != null && files.stream()
                .anyMatch(f -> f != null && f.getMimeType() != null && f.getMimeType().startsWith("image/"));
    }

    @Override
    public AiResponse transcribe(AiTranscriptionRequest request) {
        return ProviderErrors.unsupported(getProvider(), "audio transcription");
    }
}

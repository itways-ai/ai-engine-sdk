package com.itways.assistant.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.ai.config.AiEngineAutoConfiguration;
import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiError;
import com.itways.assistant.ai.dto.AiError.Kind;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiRequestConfig;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTool;
import com.itways.assistant.ai.dto.AiToolCall;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;
import com.itways.assistant.ai.dto.AiWrappedFile;
import com.itways.assistant.ai.service.AiAgent;
import com.itways.assistant.ai.service.AiService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link OllamaAgent} against a stand-in Ollama on a loopback {@link HttpServer},
 * through the client the auto-configuration builds for it
 * ({@link OllamaAgent#restTemplate(Duration)}): real HTTP, so the read timeout
 * and a refused connection are the real thing.
 */
class OllamaAgentTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCOUNT_KEY = "sk-live-0123456789abcdef";

    private HttpServer server;
    private ExecutorService handlers;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile long delayMs;
    private String baseUrl;

    /** One request as the stand-in received it. */
    private record Seen(String method, String path, String authorization, Map<String, Object> body) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> request = JSON.readValue(exchange.getRequestBody(), Map.class);
                seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"), request));
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException clientGone) {
                // The timeout test hangs up first.
            } finally {
                exchange.close();
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        handlers.shutdownNow();
    }

    private OllamaAgent agent(Duration readTimeout) {
        return new OllamaAgent(baseUrl, null, OllamaAgent.restTemplate(readTimeout));
    }

    private void respond(int status, String body) {
        this.status = status;
        this.body = body;
    }

    private static AiRequestConfig config(String apiKey, String model) {
        return AiRequestConfig.builder().provider("OLLAMA").apiKey(apiKey).model(model).build();
    }

    private static AiChatRequest hello(AiRequestConfig config) {
        return AiChatRequest.builder().config(config).messages(List.of(AiMessage.user("Hello"))).build();
    }

    private static AiError failed(AiResponse response, Kind kind) {
        assertThat(response.isError()).as("isError").isTrue();
        assertThat(response.getContent()).as("content").isNull();
        assertThat(response.hasToolCalls()).isFalse();
        assertThat(response.getError().getKind()).isEqualTo(kind);
        assertThat(response.getError().getProvider()).isEqualTo("OLLAMA");
        return response.getError();
    }

    // ── chat ─────────────────────────────────────────────────────────────────

    @Test
    void plainChatGoesToTheOpenAiCompatibleEndpointWithoutAKey() {
        respond(200, """
                {"id":"chatcmpl-1","object":"chat.completion","model":"qwen3:8b",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"Hi there"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}
                """);

        // The account carries a key (a hosted provider's, say): it must never be sent.
        AiResponse response = agent(Duration.ofSeconds(5)).chat(hello(config(ACCOUNT_KEY, null)));

        assertThat(response.isError()).isFalse();
        assertThat(response.getContent()).isEqualTo("Hi there");
        assertThat(response.getModel()).isEqualTo("qwen3:8b");
        assertThat(response.getUsage().getPromptTokens()).isEqualTo(12);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(15);
        assertThat(seen).hasSize(1);
        Seen request = seen.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/chat/completions");
        assertThat(request.authorization()).isNull();
        assertThat(request.body()).containsEntry("model", OllamaAgent.DEFAULT_MODEL).containsEntry("stream", false)
                // Thinking off: on /v1 only reasoning_effort does it ("think" is ignored there).
                .containsEntry("reasoning_effort", "none")
                .doesNotContainKeys("temperature", "max_tokens", "tools", "think");
        assertThat(request.body().toString()).doesNotContain(ACCOUNT_KEY);
        assertThat(request.body().get("messages"))
                .isEqualTo(List.of(Map.of("role", "user", "content", "Hello")));
    }

    @Test
    void theDefaultModelIsQwen3WithThinkingOff() {
        assertThat(OllamaAgent.DEFAULT_MODEL).isEqualTo("qwen3:8b");
        assertThat(OllamaAgent.DEFAULT_REASONING_EFFORT).isEqualTo("none");
        assertThat(agent(Duration.ofSeconds(5)).reasoningEffort()).isEqualTo("none");
    }

    @Test
    void aConfiguredReasoningEffortIsSentAndABlankOneIsNot() {
        respond(200, """
                {"model":"qwen3:8b","choices":[{"message":{"role":"assistant","content":"ok"}}]}
                """);

        new OllamaAgent(baseUrl, null, " low ", OllamaAgent.restTemplate(Duration.ofSeconds(5)))
                .chat(hello(config(null, null)));
        new OllamaAgent(baseUrl, null, "  ", OllamaAgent.restTemplate(Duration.ofSeconds(5)))
                .chat(hello(config(null, null)));
        new OllamaAgent(baseUrl, null, null, OllamaAgent.restTemplate(Duration.ofSeconds(5)))
                .chat(hello(config(null, null)));

        assertThat(seen.get(0).body()).containsEntry("reasoning_effort", "low");
        assertThat(seen.get(1).body()).doesNotContainKey("reasoning_effort");
        assertThat(seen.get(2).body()).doesNotContainKey("reasoning_effort");
    }

    @Test
    void aThinkBlockInTheContentIsStripped() {
        respond(200, """
                {"model":"qwen3:8b","choices":[{"message":{"role":"assistant",
                 "content":"<think>\\nThe user asks in Arabic.\\n</think>\\n\\nمرحبا، كيف أساعدك؟"}}]}
                """);

        AiResponse response = agent(Duration.ofSeconds(5)).chat(hello(config(null, null)));

        assertThat(response.isError()).isFalse();
        assertThat(response.getContent()).isEqualTo("مرحبا، كيف أساعدك؟");
    }

    @Test
    void contentWithoutAThinkBlockIsLeftAsItIs() {
        assertThat(OllamaAgent.withoutThinking("  Hi <b>there</b>\n")).isEqualTo("  Hi <b>there</b>\n");
        assertThat(OllamaAgent.withoutThinking("<THINK></THINK>\n\nA")).isEqualTo("A");
        assertThat(OllamaAgent.withoutThinking("<think>a</think>B <think>c</think>")).isEqualTo("B");
        assertThat(OllamaAgent.withoutThinking("<think>\n\n</think>\n\n")).isEmpty();
    }

    @Test
    void theAccountsModelAndSamplingSettingsAreSent() {
        respond(200, """
                {"model":"llama3.2:3b","choices":[{"message":{"role":"assistant","content":"ok"}}]}
                """);
        AiChatRequest request = hello(config(null, "llama3.2:3b"));
        request.setTemperature(0.2);
        request.setMaxTokens(700);

        agent(Duration.ofSeconds(5)).chat(request);

        assertThat(seen.get(0).body()).containsEntry("model", "llama3.2:3b").containsEntry("temperature", 0.2)
                .containsEntry("max_tokens", 700);
    }

    @Test
    void toolsAreDeclaredAndAToolCallComesBack() {
        respond(200, """
                {"model":"qwen3:8b","choices":[{"index":0,"finish_reason":"tool_calls",
                 "message":{"role":"assistant","content":"",
                  "tool_calls":[{"id":"call_abc123","index":0,"type":"function",
                   "function":{"name":"start_journey","arguments":"{\\"intent\\":\\"BOOK_VISIT\\"}"}}]}}],
                 "usage":{"prompt_tokens":80,"completion_tokens":20,"total_tokens":100}}
                """);
        AiTool tool = AiTool.of("start_journey", "Start a workflow.",
                AiTool.object(Map.of("intent", AiTool.stringEnum("The workflow.", List.of("BOOK_VISIT"))),
                        List.of("intent")));
        AiChatRequest request = AiChatRequest.builder().config(config(null, null))
                .messages(List.of(AiMessage.system("Be brief."), AiMessage.user("I want to book a visit")))
                .tools(List.of(tool)).build();

        AiResponse response = agent(Duration.ofSeconds(5)).chat(request);

        assertThat(response.isError()).isFalse();
        assertThat(response.getToolCalls()).hasSize(1);
        AiToolCall call = response.getToolCalls().get(0);
        assertThat(call.getId()).isEqualTo("call_abc123");
        assertThat(call.getName()).isEqualTo("start_journey");
        assertThat(call.argument("intent")).isEqualTo("BOOK_VISIT");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) seen.get(0).body().get("tools");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0)).containsEntry("type", "function");
        assertThat(((Map<?, ?>) tools.get(0).get("function")).get("name")).isEqualTo("start_journey");
    }

    @Test
    void aReplayedToolTurnIsSentWithTextContentAndTheResultById() {
        respond(200, """
                {"model":"qwen3:8b","choices":[{"message":{"role":"assistant","content":"Booked."}}]}
                """);
        AiToolCall asked = AiToolCall.of("call_1", "lookup", Map.of("id", "42"));
        AiChatRequest request = AiChatRequest.builder().config(config(null, null))
                .messages(List.of(AiMessage.user("Book it"), AiMessage.toolRequest(null, List.of(asked)),
                        AiMessage.toolResult(asked, "{\"free\":true}")))
                .build();

        AiResponse response = agent(Duration.ofSeconds(5)).chat(request);

        assertThat(response.getContent()).isEqualTo("Booked.");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) seen.get(0).body().get("messages");
        // Ollama rejects a null content outside a tool turn; "" is always accepted.
        assertThat(messages.get(1)).containsEntry("role", "assistant").containsEntry("content", "")
                .containsKey("tool_calls");
        assertThat(messages.get(2)).containsEntry("role", "tool").containsEntry("tool_call_id", "call_1")
                .containsEntry("content", "{\"free\":true}");
    }

    @Test
    void aBaseUrlWithATrailingSlashOrV1ReachesTheSameEndpoint() {
        assertThat(OllamaAgent.chatUrl("http://host.docker.internal:11434/"))
                .isEqualTo("http://host.docker.internal:11434/v1/chat/completions");
        assertThat(OllamaAgent.chatUrl(" http://localhost:11434/v1/ "))
                .isEqualTo("http://localhost:11434/v1/chat/completions");
        assertThatThrownBy(() -> OllamaAgent.chatUrl(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    // ── what it cannot do ────────────────────────────────────────────────────

    @Test
    void imagesAndTranscriptionAreUnsupportedWithoutACall() {
        AiChatRequest withImage = AiChatRequest.builder().config(config(null, null))
                .messages(List.of(AiMessage.user("What is this?")))
                .files(List.of(AiWrappedFile.builder().content(new byte[] { 1 }).filename("a.png")
                        .mimeType("image/png").build()))
                .build();

        AiError image = failed(agent(Duration.ofSeconds(5)).chat(withImage), Kind.BAD_REQUEST);
        AiError audio = failed(agent(Duration.ofSeconds(5)).transcribe(AiTranscriptionRequest.builder()
                .config(config(null, null)).audioData(new byte[] { 1 }).filename("clip.ogg").build()),
                Kind.BAD_REQUEST);

        assertThat(image.getMessage()).isEqualTo("OLLAMA does not support images");
        assertThat(audio.getMessage()).isEqualTo("OLLAMA does not support audio transcription");
        assertThat(seen).isEmpty();
    }

    // ── failures ─────────────────────────────────────────────────────────────

    @Test
    void aModelThatIsNotPulledIsABadRequestWithOllamasMessage() {
        respond(404, """
                {"error":{"message":"model \\"qwen3:8b\\" not found, try pulling it first",
                 "type":"api_error","param":null,"code":null}}
                """);

        AiError error = failed(agent(Duration.ofSeconds(5)).chat(hello(config(null, null))), Kind.BAD_REQUEST);

        assertThat(error.getProviderStatus()).isEqualTo(404);
        assertThat(error.getMessage()).isEqualTo("model \"qwen3:8b\" not found, try pulling it first");
    }

    @Test
    void serverErrorsAndOverloadAreRetryableAndRateLimitsAreRateLimited() {
        respond(500, "{\"error\":{\"message\":\"llama runner process has terminated\"}}");
        AiError crashed = failed(agent(Duration.ofSeconds(5)).chat(hello(config(null, null))), Kind.UNAVAILABLE);
        assertThat(crashed.isRetryable()).isTrue();
        assertThat(crashed.getMessage()).isEqualTo("llama runner process has terminated");

        respond(503, "{\"error\":{\"message\":\"server busy, please try again\"}}");
        failed(agent(Duration.ofSeconds(5)).chat(hello(config(null, null))), Kind.UNAVAILABLE);

        respond(429, "{\"error\":{\"message\":\"too many requests\"}}");
        failed(agent(Duration.ofSeconds(5)).chat(hello(config(null, null))), Kind.RATE_LIMITED);
    }

    @Test
    void anEmptyAnswerIsAnErrorNotABlankReply() {
        respond(200, "{\"model\":\"qwen3:8b\",\"choices\":[]}");

        failed(agent(Duration.ofSeconds(5)).chat(hello(config(null, null))), Kind.OTHER);
    }

    @Test
    void aModelThatDoesNotAnswerWithinTheReadTimeoutIsATimeout() {
        respond(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"late\"}}]}");
        delayMs = 1_500;

        long started = System.nanoTime();
        AiError error = failed(agent(Duration.ofMillis(300)).chat(hello(config(null, null))), Kind.TIMEOUT);
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(error.getProviderStatus()).isNull();
        assertThat(error.isRetryable()).isTrue();
        assertThat(tookMs).isLessThan(1_400);
    }

    @Test
    void anOllamaThatIsNotRunningIsUnavailable() throws IOException {
        try (ServerSocket free = new ServerSocket(0)) {
            baseUrl = "http://127.0.0.1:" + free.getLocalPort();
        }
        // Nothing listens there now.

        AiError error = failed(agent(Duration.ofSeconds(2)).chat(hello(config(null, null))), Kind.UNAVAILABLE);

        assertThat(error.getMessage()).contains("could not be reached");
    }

    @Test
    void aReadTimeoutMustBePositive() {
        assertThatThrownBy(() -> OllamaAgent.restTemplate(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ai.ollama.read-timeout");
    }

    // ── auto-configuration ───────────────────────────────────────────────────

    /** The SDK as a service loads it; the service supplies the ObjectMapper. */
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(AiEngineAutoConfiguration.class);

    @Test
    void theAgentExistsOnlyWhenABaseUrlIsSet() {
        context.run(ctx -> assertThat(ctx).doesNotHaveBean(OllamaAgent.class));
        context.withPropertyValues("ai.ollama.base-url=  ")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(OllamaAgent.class));
        context.withPropertyValues("ai.ollama.base-url=" + baseUrl).run(ctx -> {
            assertThat(ctx).hasSingleBean(OllamaAgent.class);
            @SuppressWarnings("unchecked")
            Map<String, AiAgent> agents = ctx.getBean("aiAgents", Map.class);
            assertThat(agents).containsKey("OLLAMA");
            assertThat(ctx.getBean(OllamaAgent.class).chatUrl()).isEqualTo(baseUrl + "/v1/chat/completions");
        });
    }

    @Test
    void aServiceReachesTheConfiguredOllamaWithItsReadTimeout() {
        respond(200, "{\"model\":\"qwen3:8b\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"late\"}}]}");
        delayMs = 1_000;

        context.withPropertyValues("ai.ollama.base-url=" + baseUrl + "/", "ai.ollama.read-timeout=300ms")
                .run(ctx -> failed(ctx.getBean(AiService.class).chat(hello(config(null, null))), Kind.TIMEOUT));

        delayMs = 0;
        context.withPropertyValues("ai.ollama.base-url=" + baseUrl, "ai.ollama.read-timeout=5s",
                "ai.ollama.default-model=llama3.2:3b").run(ctx -> {
                    AiResponse response = ctx.getBean(AiService.class).chat(hello(config(null, null)));
                    assertThat(response.getContent()).isEqualTo("late");
                });
        assertThat(seen.get(seen.size() - 1).body()).containsEntry("model", "llama3.2:3b")
                .containsEntry("reasoning_effort", "none");
    }

    @Test
    void theReasoningEffortPropertyIsSentAndBlankSendsNothing() {
        respond(200, "{\"model\":\"qwen3:8b\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}");

        context.withPropertyValues("ai.ollama.base-url=" + baseUrl).run(ctx -> {
            assertThat(ctx.getBean(OllamaAgent.class).reasoningEffort()).isEqualTo("none");
            ctx.getBean(AiService.class).chat(hello(config(null, null)));
        });
        assertThat(seen.get(seen.size() - 1).body()).containsEntry("model", "qwen3:8b")
                .containsEntry("reasoning_effort", "none");

        context.withPropertyValues("ai.ollama.base-url=" + baseUrl, "ai.ollama.reasoning-effort=").run(ctx -> {
            assertThat(ctx.getBean(OllamaAgent.class).reasoningEffort()).isNull();
            ctx.getBean(AiService.class).chat(hello(config(null, null)));
        });
        assertThat(seen.get(seen.size() - 1).body()).doesNotContainKey("reasoning_effort");

        context.withPropertyValues("ai.ollama.base-url=" + baseUrl, "ai.ollama.reasoning-effort=low")
                .run(ctx -> ctx.getBean(AiService.class).chat(hello(config(null, null))));
        assertThat(seen.get(seen.size() - 1).body()).containsEntry("reasoning_effort", "low");
    }
}

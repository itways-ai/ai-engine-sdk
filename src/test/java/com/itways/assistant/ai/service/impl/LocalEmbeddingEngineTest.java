package com.itways.assistant.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The engine against a stub Ollama on a loopback port: real HTTP for both
 * {@code /api/embed} and the per-text {@code /api/embeddings} (through
 * langchain4j), only the model replaced. A stub vector encodes its text: element
 * 0 is the text's length, element 1 is 1, the rest 0. {@code /api/embed} vectors
 * come back as sent; per-text vectors are normalised, so there the length is
 * {@code v[0] / v[1]}.
 */
class LocalEmbeddingEngineTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DIM = LocalEmbeddingEngine.DEFAULT_DIMENSIONS;
    private static final String CONTEXT_ERROR = "{\"error\":\"the input length exceeds the context length\"}";

    /** Bodies of the calls to {@code /api/embed} and {@code /api/embeddings}, in order. */
    private final List<Map<String, Object>> batchCalls = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> perTextCalls = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);

    /** What the stub's /api/embed does; the default embeds every input. */
    private volatile Reply batchReply = this::embedAll;
    /** What the stub's /api/embeddings does; the default embeds the prompt. */
    private volatile Reply perTextReply = body -> ok(Map.of("embedding", vector(prompt(body).length(), DIM)));
    /** Inputs longer than this are refused as over the context length, when set. */
    private volatile int contextLimit = Integer.MAX_VALUE;

    private HttpServer ollama;
    private String url;

    @BeforeEach
    void startStubOllama() throws IOException {
        ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama.createContext("/api/embed", exchange -> serve(exchange, batchCalls, batchReply));
        ollama.createContext("/api/embeddings", exchange -> serve(exchange, perTextCalls, perTextReply));
        ollama.start();
        url = "http://127.0.0.1:" + ollama.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        release.countDown();
        ollama.stop(0);
    }

    // ── /api/embed ────────────────────────────────────────────────────────────

    @Test
    void embedAsksTheBatchEndpointWithTheModel() {
        LocalEmbeddingEngine engine = new LocalEmbeddingEngine(url);

        float[] vector = engine.embed("hello");

        assertThat(vector).hasSize(DIM);
        assertThat(vector[0]).isEqualTo(5f);
        assertThat(batchCalls).singleElement().satisfies(call -> {
            assertThat(call.get("model")).isEqualTo("granite-embedding:278m");
            assertThat(call.get("input")).isEqualTo(List.of("hello"));
            assertThat(call.get("truncate")).isEqualTo(false);
        });
        assertThat(batchCalls.get(0)).doesNotContainKey("dimensions");
        assertThat(perTextCalls).isEmpty();
        assertThat(engine.modelId()).isEqualTo("granite-embedding:278m");
        assertThat(engine.dimensions()).isEqualTo(768);
        assertThat(engine.queryPrefix()).isEmpty();
    }

    @Test
    void fortyTextsTakeTwoCallsOfAtMostThirtyTwoAndKeepTheirOrder() {
        List<String> texts = texts(40);

        List<float[]> vectors = new LocalEmbeddingEngine(url).embedBatch(texts);

        assertThat(batchCalls).hasSize(2);
        assertThat(inputs(0)).hasSize(32).isEqualTo(texts.subList(0, 32));
        assertThat(inputs(1)).hasSize(8).isEqualTo(texts.subList(32, 40));
        assertThat(vectors).hasSize(40);
        for (int i = 0; i < 40; i++) {
            assertThat(vectors.get(i)[0]).as("vector %d", i).isEqualTo(i + 1f);
        }
        assertThat(perTextCalls).isEmpty();
    }

    @Test
    void theBatchSizeIsConfigurable() {
        List<float[]> vectors = engine(10, false, 5_000).embedBatch(texts(25));

        assertThat(batchCalls).extracting(call -> ((List<?>) call.get("input")).size()).containsExactly(10, 10, 5);
        assertThat(vectors).hasSize(25);
    }

    @Test
    void truncationIsOffUnlessConfigured() {
        engine(32, true, 5_000).embed("hello");

        assertThat(batchCalls).singleElement().satisfies(call -> assertThat(call.get("truncate")).isEqualTo(true));
    }

    // ── Asked dimensions and the query prefix (1.3.0) ─────────────────────────

    @Test
    void askedDimensionsGoToOllamaAndNameTheModel() {
        LocalEmbeddingEngine engine = LocalEmbeddingEngine.builder(url).model("snowflake-arctic-embed2")
                .askedDimensions(256).build();

        List<float[]> vectors = engine.embedBatch(texts(3));

        assertThat(vectors).allSatisfy(vector -> assertThat(vector).hasSize(256));
        assertThat(batchCalls).singleElement().satisfies(call -> {
            assertThat(call.get("model")).isEqualTo("snowflake-arctic-embed2");
            assertThat(call.get("dimensions")).isEqualTo(256);
        });
        assertThat(engine.modelId()).isEqualTo("snowflake-arctic-embed2@256");
        assertThat(engine.model()).isEqualTo("snowflake-arctic-embed2");
        assertThat(engine.dimensions()).isEqualTo(256);
    }

    @Test
    void aVectorOfAnotherSizeThanAskedIsRefused() {
        // An Ollama that ignores "dimensions" answers with the model's full size.
        batchReply = body -> ok(Map.of("embeddings", inputs(body).stream().map(t -> vector(1, 1024)).toList()));
        LocalEmbeddingEngine engine = LocalEmbeddingEngine.builder(url).model("snowflake-arctic-embed2")
                .askedDimensions(768).build();

        assertThatThrownBy(() -> engine.embed("hello"))
                .isInstanceOfSatisfying(EmbeddingDimensionException.class, e -> {
                    assertThat(e.getExpected()).isEqualTo(768);
                    assertThat(e.getActual()).isEqualTo(1024);
                });
    }

    @Test
    void aQueryGetsThePrefixAndAPassageDoesNot() {
        LocalEmbeddingEngine engine = LocalEmbeddingEngine.builder(url).queryPrefix("query: ").build();

        engine.embedQuery("opening hours");
        engine.embedQueries(List.of("where", "when"));
        engine.embed("opening hours");
        engine.embedBatch(List.of("where", "when"));

        assertThat(batchCalls).extracting(call -> call.get("input")).containsExactly(
                List.of("query: opening hours"), List.of("query: where", "query: when"),
                List.of("opening hours"), List.of("where", "when"));
        assertThat(engine.queryPrefix()).isEqualTo("query: ");
    }

    @Test
    void withoutAPrefixAQueryIsEmbeddedAsItIs() {
        LocalEmbeddingEngine engine = new LocalEmbeddingEngine(url);

        engine.embedQuery("opening hours");

        assertThat(inputs(0)).containsExactly("opening hours");
    }

    @Test
    void aPrefixLosesNoSpaceToEnvFilesAndBlankIsNone() {
        assertThat(LocalEmbeddingEngine.builder(url).queryPrefix("query:").build().queryPrefix())
                .isEqualTo("query: ");
        assertThat(LocalEmbeddingEngine.builder(url).queryPrefix("  query: ").build().queryPrefix())
                .isEqualTo("query: ");
        assertThat(LocalEmbeddingEngine.builder(url).queryPrefix("  ").build().queryPrefix()).isEmpty();
        assertThat(LocalEmbeddingEngine.builder(url).queryPrefix(null).build().queryPrefix()).isEmpty();
    }

    @Test
    void blankQueriesAreRejectedBeforeAnyCall() {
        LocalEmbeddingEngine engine = LocalEmbeddingEngine.builder(url).queryPrefix("query: ").build();

        assertThatThrownBy(() -> engine.embedQuery(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.embedQuery(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.embedQueries(List.of("a", ""))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Text 1");
        assertThat(engine.embedQueries(List.of())).isEmpty();
        assertThat(engine.embedQueries(null)).isEmpty();
        assertThat(batchCalls).isEmpty();
    }

    @Test
    void aSecondEngineHasItsOwnModelSizeAndPrefixAndTheFirstIsUnchanged() {
        LocalEmbeddingEngine intents = engine(10, true, 5_000);

        LocalEmbeddingEngine knowledge = intents.toBuilder().model("snowflake-arctic-embed2").askedDimensions(768)
                .queryPrefix("query: ").build();
        knowledge.embedQuery("hello");
        knowledge.embedBatch(texts(12));
        intents.embed("hello");

        assertThat(knowledge.modelId()).isEqualTo("snowflake-arctic-embed2@768");
        assertThat(intents.modelId()).isEqualTo("granite-embedding:278m");
        assertThat(intents.queryPrefix()).isEmpty();
        // The second engine keeps the first one's batch size and truncation.
        assertThat(batchCalls).extracting(call -> ((List<?>) call.get("input")).size()).containsExactly(1, 10, 2, 1);
        assertThat(batchCalls.subList(0, 3)).allSatisfy(call -> {
            assertThat(call).containsEntry("model", "snowflake-arctic-embed2").containsEntry("dimensions", 768)
                    .containsEntry("truncate", true);
        });
        assertThat(batchCalls.get(0).get("input")).isEqualTo(List.of("query: hello"));
        assertThat(batchCalls.get(3)).containsEntry("model", "granite-embedding:278m").doesNotContainKey("dimensions")
                .containsEntry("input", List.of("hello"));
    }

    @Test
    void aBuilderWithoutAskedDimensionsSendsNone() {
        LocalEmbeddingEngine engine = new LocalEmbeddingEngine(url).toBuilder().model("other:1b").askedDimensions(768)
                .askedDimensions(null).build();

        engine.embed("hello");

        assertThat(batchCalls.get(0)).doesNotContainKey("dimensions");
        assertThat(engine.modelId()).isEqualTo("other:1b");
        assertThat(engine.dimensions()).isEqualTo(768);
    }

    @Test
    void thePerTextPathCutsAnAskedSizeItself() {
        batchReply = body -> new Response(404, "404 page not found");
        perTextReply = body -> ok(Map.of("embedding", vector(prompt(body).length(), 1024)));
        LocalEmbeddingEngine engine = LocalEmbeddingEngine.builder(url).model("snowflake-arctic-embed2")
                .askedDimensions(768).queryPrefix("query: ").build();

        float[] vector = engine.embedQuery("hello");

        assertThat(vector).hasSize(768);
        assertThat(norm(vector)).isCloseTo(1.0, within(1e-5));
        assertThat(vector[0] / vector[1]).isCloseTo("query: hello".length(), within(1e-3f));
        assertThat(prompt(perTextCalls.get(0))).isEqualTo("query: hello");
    }

    // ── Older Ollama: no /api/embed ───────────────────────────────────────────

    @Test
    void anOllamaWithoutTheBatchEndpointIsAskedOneTextAtATime() {
        batchReply = body -> new Response(404, "404 page not found");
        LocalEmbeddingEngine engine = new LocalEmbeddingEngine(url);

        List<float[]> vectors = engine.embedBatch(texts(40));

        assertThat(batchCalls).hasSize(1);
        assertThat(perTextCalls).hasSize(40);
        assertThat(perTextCalls).extracting(call -> call.get("model")).containsOnly("granite-embedding:278m");
        for (int i = 0; i < 40; i++) {
            float[] vector = vectors.get(i);
            assertThat(vector).hasSize(DIM);
            assertThat(vector[0] / vector[1]).as("vector %d", i).isCloseTo(i + 1f, within(1e-3f));
            assertThat(norm(vector)).isCloseTo(1.0, within(1e-5));
        }

        // Remembered: the next calls go straight to the per-text path.
        engine.embedBatch(texts(3));
        engine.embed("hello");
        assertThat(batchCalls).hasSize(1);
        assertThat(perTextCalls).hasSize(44);
    }

    @Test
    void aMissingModelIsAnErrorAndNotAReasonToSwitchEndpoints() {
        batchReply = body -> new Response(404,
                "{\"error\":\"model \\\"granite-embedding:278m\\\" not found, try pulling it first\"}");
        LocalEmbeddingEngine engine = new LocalEmbeddingEngine(url);

        assertThatThrownBy(() -> engine.embedBatch(texts(3)))
                .isExactlyInstanceOf(EmbeddingException.class)
                .hasMessageContaining("HTTP 404")
                .hasMessageContaining("not found, try pulling it first");
        assertThatThrownBy(() -> engine.embed("hello")).isExactlyInstanceOf(EmbeddingException.class);
        assertThat(batchCalls).hasSize(2);
        assertThat(perTextCalls).isEmpty();
    }

    // ── Vector checks ─────────────────────────────────────────────────────────

    @Test
    void vectorsOfAnotherSizeAreRefused() {
        batchReply = body -> ok(Map.of("embeddings", inputs(body).stream().map(t -> vector(1, 1024)).toList()));

        assertThatThrownBy(() -> new LocalEmbeddingEngine(url).embedBatch(texts(3)))
                .isInstanceOfSatisfying(EmbeddingDimensionException.class, e -> {
                    assertThat(e.getExpected()).isEqualTo(768);
                    assertThat(e.getActual()).isEqualTo(1024);
                })
                .hasMessageContaining("granite-embedding:278m");
    }

    @Test
    void vectorsOfAnotherSizeAreRefusedOnThePerTextPathToo() {
        batchReply = body -> new Response(404, "404 page not found");
        perTextReply = body -> ok(Map.of("embedding", vector(1, 384)));

        assertThatThrownBy(() -> new LocalEmbeddingEngine(url).embed("hello"))
                .isInstanceOfSatisfying(EmbeddingDimensionException.class,
                        e -> assertThat(e.getActual()).isEqualTo(384));
    }

    @Test
    void fewerVectorsThanTextsIsAnError() {
        batchReply = body -> ok(Map.of("embeddings", List.of(vector(1, DIM))));

        assertThatThrownBy(() -> new LocalEmbeddingEngine(url).embedBatch(texts(2)))
                .isExactlyInstanceOf(EmbeddingException.class)
                .hasMessageContaining("returned 1 vectors for 2 texts");
    }

    // ── Longer than the model's context ───────────────────────────────────────

    @Test
    void aTextLongerThanTheContextIsNamedByItsPosition() {
        contextLimit = 1_000;
        List<String> texts = new ArrayList<>(texts(35));
        texts.set(33, "x".repeat(4_700));

        assertThatThrownBy(() -> new LocalEmbeddingEngine(url).embedBatch(texts))
                .isInstanceOfSatisfying(EmbeddingInputTooLongException.class, e -> {
                    assertThat(e.getIndex()).isEqualTo(33);
                    assertThat(e.getLength()).isEqualTo(4_700);
                })
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("Text 33 (4700 characters)")
                .hasMessageContaining("the input length exceeds the context length");
        // 0..31 fine; 32..34 refused; then 32 and 33 one at a time.
        assertThat(batchCalls).extracting(call -> call.get("input")).containsExactly(
                texts.subList(0, 32), texts.subList(32, 35), List.of(texts.get(32)), List.of(texts.get(33)));
    }

    @Test
    void aSingleTooLongQueryIsTyped() {
        contextLimit = 1_000;

        assertThatThrownBy(() -> new LocalEmbeddingEngine(url).embed("y".repeat(4_700)))
                .isInstanceOfSatisfying(EmbeddingInputTooLongException.class,
                        e -> assertThat(e.getIndex()).isZero());
        assertThat(batchCalls).hasSize(1);
    }

    @Test
    void aTextLongerThanTheContextOnThePerTextPathIsTypedToo() {
        batchReply = body -> new Response(404, "404 page not found");
        perTextReply = body -> prompt(body).length() > 1_000
                ? new Response(500, CONTEXT_ERROR)
                : ok(Map.of("embedding", vector(prompt(body).length(), DIM)));

        assertThatThrownBy(() -> new LocalEmbeddingEngine(url).embedBatch(List.of("short", "z".repeat(4_700))))
                .isInstanceOfSatisfying(EmbeddingInputTooLongException.class, e -> {
                    assertThat(e.getIndex()).isEqualTo(1);
                    assertThat(e.getLength()).isEqualTo(4_700);
                });
        assertThat(perTextCalls).hasSize(2); // no retries
    }

    // ── Other failures ────────────────────────────────────────────────────────

    @Test
    void anotherOllamaErrorCarriesItsMessage() {
        batchReply = body -> new Response(500, "{\"error\":\"llama runner process has terminated\"}");

        assertThatThrownBy(() -> new LocalEmbeddingEngine(url).embed("hello"))
                .isExactlyInstanceOf(EmbeddingException.class)
                .hasMessage("Ollama /api/embed answered HTTP 500: llama runner process has terminated");
        assertThat(batchCalls).hasSize(1);
    }

    @Test
    void aStalledOllamaTimesOut() {
        batchReply = body -> {
            release.await(10, TimeUnit.SECONDS);
            return embedAll(body);
        };
        LocalEmbeddingEngine engine = engine(32, false, 300);

        long start = System.nanoTime();
        assertThatThrownBy(() -> engine.embed("hello"))
                .isExactlyInstanceOf(EmbeddingException.class)
                .hasMessageContaining("did not answer within 300 ms")
                .hasCauseInstanceOf(HttpTimeoutException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(5_000);
    }

    @Test
    void anUnreachableOllamaIsAnEmbeddingException() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> new LocalEmbeddingEngine("http://127.0.0.1:" + closedPort).embed("hello"))
                .isExactlyInstanceOf(EmbeddingException.class)
                .hasMessageContaining("is unreachable");
    }

    // ── Input checks and settings ─────────────────────────────────────────────

    @Test
    void emptyBatchMakesNoCalls() {
        LocalEmbeddingEngine engine = new LocalEmbeddingEngine(url);

        assertThat(engine.embedBatch(List.of())).isEmpty();
        assertThat(engine.embedBatch(null)).isEmpty();
        assertThat(batchCalls).isEmpty();
    }

    @Test
    void blankInputIsRejectedBeforeAnyCall() {
        LocalEmbeddingEngine engine = new LocalEmbeddingEngine(url);
        List<String> withNull = new ArrayList<>(List.of("a", "b"));
        withNull.add(null);

        assertThatThrownBy(() -> engine.embed("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.embed(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.embedBatch(List.of("a", " "))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Text 1");
        assertThatThrownBy(() -> engine.embedBatch(withNull)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Text 2");
        assertThat(batchCalls).isEmpty();
        assertThat(perTextCalls).isEmpty();
    }

    @Test
    void impossibleSettingsAreRefusedAtStartup() {
        assertThatThrownBy(() -> engine(0, false, 5_000)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ai.embedding.batch-size");
        assertThatThrownBy(() -> new LocalEmbeddingEngine(url, " ", DIM, 32, false, 5_000, 5_000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ai.embedding.model");
    }

    @Test
    void springBindsTheEmbeddingProperties() {
        new ApplicationContextRunner()
                .withBean(LocalEmbeddingEngine.class)
                .withPropertyValues("ai.embedding.ollama-url=" + url + "/", "ai.embedding.model=other-model:1b",
                        "ai.embedding.batch-size=10", "ai.embedding.truncate=true")
                .run(context -> {
                    LocalEmbeddingEngine engine = context.getBean(LocalEmbeddingEngine.class);
                    assertThat(engine.modelId()).isEqualTo("other-model:1b");
                    assertThat(engine.dimensions()).isEqualTo(768);
                    engine.embedBatch(texts(25));
                });

        assertThat(batchCalls).hasSize(3);
        assertThat(batchCalls.get(0)).containsEntry("model", "other-model:1b").containsEntry("truncate", true)
                .doesNotContainKey("dimensions");
    }

    @Test
    void springBindsTheAskedDimensionsAndTheQueryPrefix() {
        new ApplicationContextRunner()
                .withBean(LocalEmbeddingEngine.class)
                .withPropertyValues("ai.embedding.ollama-url=" + url, "ai.embedding.model=snowflake-arctic-embed2",
                        "ai.embedding.dimensions=256", "ai.embedding.send-dimensions=true",
                        "ai.embedding.query-prefix=query:")
                .run(context -> {
                    LocalEmbeddingEngine engine = context.getBean(LocalEmbeddingEngine.class);
                    assertThat(engine.modelId()).isEqualTo("snowflake-arctic-embed2@256");
                    assertThat(engine.queryPrefix()).isEqualTo("query: ");
                    assertThat(engine.embedQuery("hello")).hasSize(256);
                });

        assertThat(batchCalls).singleElement().satisfies(call -> {
            assertThat(call).containsEntry("dimensions", 256);
            assertThat(call.get("input")).isEqualTo(List.of("query: hello"));
        });
    }

    // ── Stub Ollama ───────────────────────────────────────────────────────────

    private LocalEmbeddingEngine engine(int batchSize, boolean truncate, int readTimeoutMs) {
        return new LocalEmbeddingEngine(url, LocalEmbeddingEngine.DEFAULT_MODEL, DIM, batchSize, truncate, 2_000,
                readTimeoutMs);
    }

    private Response embedAll(Map<String, Object> body) {
        List<String> inputs = inputs(body);
        if (inputs.stream().anyMatch(text -> text.length() > contextLimit)) {
            return new Response(400, CONTEXT_ERROR);
        }
        int size = body.get("dimensions") instanceof Number asked ? asked.intValue() : DIM;
        return ok(Map.of("model", body.get("model"), "embeddings",
                inputs.stream().map(text -> vector(text.length(), size)).toList()));
    }

    @SuppressWarnings("unchecked")
    private void serve(HttpExchange exchange, List<Map<String, Object>> calls, Reply reply) throws IOException {
        try {
            Map<String, Object> body = JSON.readValue(exchange.getRequestBody(), Map.class);
            calls.add(body);
            Response response = reply.to(body);
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type",
                    response.body().startsWith("{") ? "application/json" : "text/plain");
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (Exception e) {
            exchange.sendResponseHeaders(599, -1);
        } finally {
            exchange.close();
        }
    }

    private static Response ok(Object body) {
        try {
            return new Response(200, JSON.writeValueAsString(body));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Double> vector(int first, int size) {
        List<Double> vector = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            vector.add(i == 0 ? first : i == 1 ? 1.0 : 0.0);
        }
        return vector;
    }

    @SuppressWarnings("unchecked")
    private static List<String> inputs(Map<String, Object> body) {
        return (List<String>) body.get("input");
    }

    private List<String> inputs(int call) {
        return inputs(batchCalls.get(call));
    }

    private static String prompt(Map<String, Object> body) {
        return String.valueOf(body.get("prompt"));
    }

    /** "x", "xx", … : text i has length i + 1. */
    private static List<String> texts(int count) {
        return IntStream.rangeClosed(1, count).mapToObj("x"::repeat).toList();
    }

    private static double norm(float[] vector) {
        double sum = 0;
        for (float x : vector) {
            sum += (double) x * x;
        }
        return Math.sqrt(sum);
    }

    private record Response(int status, String body) {
    }

    @FunctionalInterface
    private interface Reply {
        Response to(Map<String, Object> body) throws Exception;
    }
}

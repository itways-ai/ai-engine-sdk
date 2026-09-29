package com.itways.assistant.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The engine against a stub Ollama on a loopback port: the real langchain4j
 * client, the real HTTP, only the model replaced by a vector derived from the
 * prompt's length.
 */
class LocalEmbeddingEngineTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    private HttpServer ollama;
    private LocalEmbeddingEngine engine;

    @BeforeEach
    void startStubOllama() throws IOException {
        ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama.createContext("/api/embeddings", this::embeddings);
        ollama.start();
        engine = new LocalEmbeddingEngine("http://127.0.0.1:" + ollama.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        ollama.stop(0);
    }

    @SuppressWarnings("unchecked")
    private void embeddings(HttpExchange exchange) throws IOException {
        Map<String, Object> body = JSON.readValue(exchange.getRequestBody(), Map.class);
        requests.add(body);
        String prompt = String.valueOf(body.get("prompt"));
        byte[] reply = JSON.writeValueAsBytes(Map.of("embedding", List.of(prompt.length(), 0.5, -1.0)));
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, reply.length);
        exchange.getResponseBody().write(reply);
        exchange.close();
    }

    @Test
    void embedsWithTheEnginesModel() {
        float[] vector = engine.embed("hello");

        assertThat(vector).containsExactly(5f, 0.5f, -1f);
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.get("model")).isEqualTo(engine.modelId());
            assertThat(request.get("prompt")).isEqualTo("hello");
        });
        assertThat(engine.modelId()).isEqualTo("granite-embedding:278m");
    }

    @Test
    void batchesKeepInputOrderAcrossMiniBatches() {
        List<String> texts = IntStream.rangeClosed(1, 40).mapToObj("x"::repeat).toList();

        List<float[]> vectors = engine.embedBatch(texts);

        assertThat(vectors).hasSize(40);
        for (int i = 0; i < 40; i++) {
            assertThat(vectors.get(i)[0]).isEqualTo(i + 1f);
        }
        assertThat(requests).hasSize(40);
    }

    @Test
    void emptyBatchMakesNoCalls() {
        assertThat(engine.embedBatch(List.of())).isEmpty();
        assertThat(engine.embedBatch(null)).isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    void blankInputIsRejectedBeforeAnyCall() {
        assertThatThrownBy(() -> engine.embed("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.embed(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void anOllamaFailureSurfacesAsAnException() {
        ollama.removeContext("/api/embeddings");
        ollama.createContext("/api/embeddings", exchange -> {
            byte[] reply = "model not found".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });

        assertThatThrownBy(() -> engine.embed("hello")).isInstanceOf(RuntimeException.class);
    }
}

package com.itways.assistant.ai.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Text embeddings from a local Ollama.
 *
 * <p>
 * {@link #embed} and {@link #embedBatch} both call {@code POST /api/embed}
 * ({@code {"model", "input": [...], "truncate"}}), at most
 * {@code ai.embedding.batch-size} texts per call, so 64 passages cost two calls
 * instead of 64. An Ollama older than 0.3.0 has no such endpoint and answers
 * "404 page not found"; the engine then embeds one text per call through
 * {@code /api/embeddings} and keeps doing so until it restarts.
 *
 * <p>
 * Every vector returned has {@code ai.embedding.dimensions} components and unit
 * length ({@code /api/embed} normalises; the per-text path is normalised here),
 * so a dot product of two of them is their cosine. Results are in input order.
 *
 * <p>
 * Failures are {@link EmbeddingException}s: {@link EmbeddingInputTooLongException}
 * for a text longer than the model's context (it names the text's position),
 * {@link EmbeddingDimensionException} for a vector of the wrong size, the base
 * class for everything else. There are no automatic retries.
 *
 * <p>
 * <b>Asked dimensions and query prefix (1.3.0).</b> A model trained for shorter
 * vectors (Matryoshka, e.g. {@code snowflake-arctic-embed2}) can be asked for
 * {@code dimensions} components: they go out as {@code "dimensions"} on
 * {@code /api/embed}, and {@link #modelId()} becomes {@code model@dimensions},
 * because the same model at another size is another vector space. A model
 * trained with an instruction for queries (e.g. {@code "query: "}) gets it from
 * {@link #embedQuery} and {@link #embedQueries} only; passages ({@link #embed},
 * {@link #embedBatch}) are embedded as they are. The Spring bean reads both from
 * {@code ai.embedding.send-dimensions} and {@code ai.embedding.query-prefix}
 * (off and none by default). A second engine with its own model, size and
 * prefix, on the same Ollama with the same batch size and timeouts, comes from
 * {@link #toBuilder()}; any engine from {@link #builder(String)}.
 */
@Slf4j
@Component // Pure Spring utility component, not an AI agent
public class LocalEmbeddingEngine {

    /** The model used when {@code ai.embedding.model} is not set. */
    public static final String DEFAULT_MODEL = "granite-embedding:278m";
    /** The vector size of {@link #DEFAULT_MODEL}, and of the columns that store it. */
    public static final int DEFAULT_DIMENSIONS = 768;
    /** Texts per {@code /api/embed} call when {@code ai.embedding.batch-size} is not set. */
    public static final int DEFAULT_BATCH_SIZE = 32;
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 60_000;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ERROR_CHARS = 300;

    private final String baseUrl;
    private final String model;
    private final int dimensions;
    /** Whether {@link #dimensions} goes to Ollama as {@code "dimensions"}. */
    private final boolean sendDimensions;
    /** Put before every query text; empty for none. */
    private final String queryPrefix;
    private final int batchSize;
    private final boolean truncate;
    private final int connectTimeoutMs;
    private final Duration readTimeout;
    private final HttpClient http;
    private final EmbeddingModel perTextModel;

    /** Set once Ollama answered {@code /api/embed} with "404 page not found". */
    private volatile boolean batchEndpointMissing;

    /**
     * An engine with every setting at its default except Ollama's address. For code
     * that builds the engine itself (tests, fakes); Spring uses the constructor
     * below.
     */
    public LocalEmbeddingEngine(String baseUrl) {
        this(baseUrl, DEFAULT_MODEL, DEFAULT_DIMENSIONS, DEFAULT_BATCH_SIZE, false, DEFAULT_CONNECT_TIMEOUT_MS,
                DEFAULT_READ_TIMEOUT_MS);
    }

    /**
     * The 1.2 settings: no dimensions sent, no query prefix. See the constructor
     * below for each parameter.
     */
    public LocalEmbeddingEngine(String baseUrl, String model, int dimensions, int batchSize, boolean truncate,
            int connectTimeoutMs, int readTimeoutMs) {
        this(baseUrl, model, dimensions, false, "", batchSize, truncate, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * @param baseUrl
     *            {@code ai.embedding.ollama-url}: Ollama's address, overridable per
     *            deployment (the knowledge base runs wherever conversation-service
     *            runs, and a host that is right for one deployment is not
     *            necessarily right for the next)
     * @param model
     *            {@code ai.embedding.model}; stored next to every vector (see
     *            {@link #modelId()})
     * @param dimensions
     *            {@code ai.embedding.dimensions}: the size every returned vector must
     *            have
     * @param sendDimensions
     *            {@code ai.embedding.send-dimensions}: also ask Ollama for vectors of
     *            that size ({@code "dimensions"} on {@code /api/embed}), for a model
     *            trained to be cut short; {@link #modelId()} then names the size
     * @param queryPrefix
     *            {@code ai.embedding.query-prefix}: put before every text
     *            {@link #embedQuery} embeds (blank: none); a space is added when it
     *            does not end with one, as env files and YAML drop trailing spaces
     * @param batchSize
     *            {@code ai.embedding.batch-size}: texts per {@code /api/embed} call
     * @param truncate
     *            {@code ai.embedding.truncate}: let Ollama cut a text that is longer
     *            than the model's context instead of refusing it. Off by default: a
     *            cut text is embedded by its beginning only, silently, so the caller
     *            is told instead ({@link EmbeddingInputTooLongException})
     * @param connectTimeoutMs
     *            {@code ai.embedding.connect-timeout-ms}
     * @param readTimeoutMs
     *            {@code ai.embedding.read-timeout-ms}: how long one call may take,
     *            including a cold model load (about 7 s)
     */
    @Autowired
    public LocalEmbeddingEngine(
            @Value("${ai.embedding.ollama-url:http://localhost:11434}") String baseUrl,
            @Value("${ai.embedding.model:" + DEFAULT_MODEL + "}") String model,
            @Value("${ai.embedding.dimensions:" + DEFAULT_DIMENSIONS + "}") int dimensions,
            @Value("${ai.embedding.send-dimensions:false}") boolean sendDimensions,
            @Value("${ai.embedding.query-prefix:}") String queryPrefix,
            @Value("${ai.embedding.batch-size:" + DEFAULT_BATCH_SIZE + "}") int batchSize,
            @Value("${ai.embedding.truncate:false}") boolean truncate,
            @Value("${ai.embedding.connect-timeout-ms:" + DEFAULT_CONNECT_TIMEOUT_MS + "}") int connectTimeoutMs,
            @Value("${ai.embedding.read-timeout-ms:" + DEFAULT_READ_TIMEOUT_MS + "}") int readTimeoutMs) {
        requireText(baseUrl, "ai.embedding.ollama-url");
        requireText(model, "ai.embedding.model");
        requirePositive(dimensions, "ai.embedding.dimensions");
        requirePositive(batchSize, "ai.embedding.batch-size");
        requirePositive(connectTimeoutMs, "ai.embedding.connect-timeout-ms");
        requirePositive(readTimeoutMs, "ai.embedding.read-timeout-ms");

        this.baseUrl = baseUrl.strip().replaceAll("/+$", "");
        this.model = model.strip();
        this.dimensions = dimensions;
        this.sendDimensions = sendDimensions;
        this.queryPrefix = prefix(queryPrefix);
        this.batchSize = batchSize;
        this.truncate = truncate;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeout = Duration.ofMillis(readTimeoutMs);
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        this.perTextModel = OllamaEmbeddingModel.builder()
                .baseUrl(this.baseUrl)
                .modelName(this.model)
                .timeout(readTimeout)
                .maxRetries(1) // one attempt, like /api/embed
                .build();

        log.info("Ollama embeddings: {} with {} ({} dimensions{}, query prefix {}, {} texts per call, truncate {})",
                this.baseUrl, this.model, dimensions, sendDimensions ? " asked" : "",
                this.queryPrefix.isEmpty() ? "none" : "'" + this.queryPrefix + "'", batchSize, truncate);
    }

    /** A builder with every setting at its default, for Ollama at {@code baseUrl}. */
    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    /**
     * A builder that starts from this engine's settings: to make a second engine on
     * the same Ollama, with the same batch size, truncation and timeouts, but its
     * own model, dimensions and query prefix. This engine is not changed.
     */
    public Builder toBuilder() {
        Builder builder = new Builder(baseUrl);
        builder.model = model;
        builder.dimensions = dimensions;
        builder.sendDimensions = sendDimensions;
        builder.queryPrefix = queryPrefix;
        builder.batchSize = batchSize;
        builder.truncate = truncate;
        builder.connectTimeoutMs = connectTimeoutMs;
        builder.readTimeoutMs = (int) readTimeout.toMillis();
        return builder;
    }

    /**
     * Which model produces this engine's vectors. Stored next to every vector,
     * because vectors from two models live in different spaces: compared with each
     * other they rank by noise, so a store has to know which ones belong together —
     * and which to recompute after the model changes.
     */
    public String modelId() {
        return sendDimensions ? model + "@" + dimensions : model;
    }

    /** The model's name as Ollama knows it, without the size {@link #modelId()} may add. */
    public String model() {
        return model;
    }

    /** The size of every vector this engine returns. */
    public int dimensions() {
        return dimensions;
    }

    /** What {@link #embedQuery} puts before a query; empty for nothing. */
    public String queryPrefix() {
        return queryPrefix;
    }

    /**
     * The vector of a query: the text with the {@linkplain #queryPrefix() query
     * prefix} before it. The same as {@link #embed} when there is no prefix.
     *
     * @throws IllegalArgumentException
     *             for a null or blank text, before any call
     * @throws EmbeddingException
     *             when no vector could be produced
     */
    public float[] embedQuery(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Input text cannot be null or blank");
        }
        return embedBatch(List.of(queryPrefix + text)).get(0);
    }

    /**
     * The vectors of several queries, each with the {@linkplain #queryPrefix()
     * query prefix} before it, in the same order. Null or empty in, empty out, with
     * no call.
     *
     * @throws IllegalArgumentException
     *             when any text is null or blank, before any call
     * @throws EmbeddingException
     *             when any vector could not be produced
     */
    public List<float[]> embedQueries(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<String> prefixed = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Text " + i + " is null or blank");
            }
            prefixed.add(queryPrefix + text);
        }
        return embedBatch(prefixed);
    }

    /**
     * The vector of one text as it is, with no query prefix: a passage, or a query
     * for a model that has no prefix ({@link #embedQuery} adds it when there is one).
     *
     * @throws IllegalArgumentException
     *             for a null or blank text, before any call
     * @throws EmbeddingException
     *             when no vector could be produced
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Input text cannot be null or blank");
        }
        return embedBatch(List.of(text)).get(0);
    }

    /**
     * The vectors of several texts, in the same order, {@code ai.embedding.batch-size}
     * texts per Ollama call. Null or empty in, empty out, with no call.
     *
     * @throws IllegalArgumentException
     *             when any text is null or blank, before any call
     * @throws EmbeddingException
     *             when any vector could not be produced; no partial result is
     *             returned
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Text " + i + " is null or blank");
            }
        }

        long start = System.nanoTime();
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> slice = texts.subList(from, Math.min(from + batchSize, texts.size()));
            vectors.addAll(embedSlice(slice, from));
        }
        log.debug("Embedded {} texts in {} ms", texts.size(), (System.nanoTime() - start) / 1_000_000);
        return vectors;
    }

    /** One {@code /api/embed} call, or one per-text call per text on an old Ollama. */
    private List<float[]> embedSlice(List<String> slice, int offset) {
        if (!batchEndpointMissing) {
            try {
                return callEmbed(slice, offset);
            } catch (BatchEndpointMissing missing) {
                batchEndpointMissing = true;
                log.warn("Ollama at {} has no /api/embed (older than 0.3.0): embedding one text per call"
                        + " through /api/embeddings until restart", baseUrl);
            }
        }
        List<float[]> vectors = new ArrayList<>(slice.size());
        for (int i = 0; i < slice.size(); i++) {
            vectors.add(embedPerText(slice.get(i), offset + i));
        }
        return vectors;
    }

    private List<float[]> callEmbed(List<String> slice, int offset) throws BatchEndpointMissing {
        HttpResponse<String> response = post("/api/embed", sendDimensions
                ? Map.of("model", model, "input", slice, "truncate", truncate, "dimensions", dimensions)
                : Map.of("model", model, "input", slice, "truncate", truncate));
        int status = response.statusCode();
        if (status == 200) {
            return readVectors(response.body(), slice.size());
        }
        String error = ollamaError(response.body());
        if (status == 404 && error == null) {
            // Gin's plain "404 page not found": the endpoint does not exist. A missing
            // model is also a 404, but with a JSON error, and is not a reason to switch.
            throw new BatchEndpointMissing();
        }
        if (error != null && isContextLengthError(error)) {
            throw tooLong(slice, offset, error);
        }
        throw new EmbeddingException(
                "Ollama /api/embed answered HTTP " + status + ": " + (error != null ? error : cap(response.body())));
    }

    /**
     * Ollama refuses the whole batch without saying which text is too long. Asks for
     * the texts one at a time to name the first offender, so the caller can split
     * or drop exactly that one.
     */
    private EmbeddingInputTooLongException tooLong(List<String> slice, int offset, String error) {
        if (slice.size() == 1) {
            return new EmbeddingInputTooLongException(model, offset, slice.get(0).length(), error, null);
        }
        for (int i = 0; i < slice.size(); i++) {
            try {
                callEmbed(List.of(slice.get(i)), offset + i);
            } catch (EmbeddingInputTooLongException named) {
                return named;
            } catch (BatchEndpointMissing impossible) {
                break;
            }
        }
        return new EmbeddingInputTooLongException(model, -1, -1, error, null);
    }

    private List<float[]> readVectors(String body, int expected) {
        float[][] embeddings;
        try {
            embeddings = JSON.readValue(body, EmbedResponse.class).embeddings();
        } catch (IOException e) {
            throw new EmbeddingException("Ollama /api/embed answered with unreadable JSON", e);
        }
        int returned = embeddings == null ? 0 : embeddings.length;
        if (returned != expected) {
            throw new EmbeddingException(
                    "Ollama /api/embed returned " + returned + " vectors for " + expected + " texts");
        }
        List<float[]> vectors = new ArrayList<>(expected);
        for (float[] vector : embeddings) {
            vectors.add(checkDimension(vector));
        }
        return vectors;
    }

    /** The pre-0.3.0 path: {@code /api/embeddings}, one text per call, through langchain4j. */
    private float[] embedPerText(String text, int index) {
        float[] vector;
        try {
            vector = perTextModel.embed(text).content().vector();
        } catch (RuntimeException e) {
            String message = messages(e);
            if (isContextLengthError(message)) {
                throw new EmbeddingInputTooLongException(model, index, text.length(),
                        "the input length exceeds the context length", e);
            }
            throw new EmbeddingException("Ollama /api/embeddings failed: " + cap(message), e);
        }
        if (sendDimensions && vector != null && vector.length > dimensions) {
            // /api/embeddings takes no "dimensions": cut the vector here, as /api/embed does.
            vector = Arrays.copyOf(vector, dimensions);
        }
        return unitLength(checkDimension(vector));
    }

    private HttpResponse<String> post(String path, Object body) {
        byte[] json;
        try {
            json = JSON.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new EmbeddingException("Could not write the Ollama request", e);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(readTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            throw new EmbeddingException("Ollama at " + baseUrl + " could not be reached in time", e);
        } catch (HttpTimeoutException e) {
            throw new EmbeddingException(
                    "Ollama at " + baseUrl + " did not answer within " + readTimeout.toMillis() + " ms", e);
        } catch (IOException e) {
            throw new EmbeddingException("Ollama at " + baseUrl + " is unreachable: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("Interrupted while waiting for Ollama", e);
        }
    }

    private float[] checkDimension(float[] vector) {
        int actual = vector == null ? 0 : vector.length;
        if (actual != dimensions) {
            throw new EmbeddingDimensionException(model, dimensions, actual);
        }
        return vector;
    }

    /** Ollama's {@code {"error": "..."}}, or null when the body is something else. */
    private static String ollamaError(String body) {
        try {
            JsonNode error = JSON.readTree(body).path("error");
            return error.isTextual() ? error.asText() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean isContextLengthError(String message) {
        return message != null && message.toLowerCase(Locale.ROOT).contains("context length");
    }

    /** Every message in the cause chain: langchain4j wraps Ollama's answer. */
    private static String messages(Throwable e) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = e; t != null && all.length() < 2_000; t = t.getCause()) {
            if (t.getMessage() != null) {
                all.append(all.isEmpty() ? "" : " / ").append(t.getMessage());
            }
        }
        return all.isEmpty() ? e.getClass().getSimpleName() : all.toString();
    }

    private static String cap(String text) {
        if (text == null) {
            return "";
        }
        String line = text.strip().replaceAll("\\s+", " ");
        return line.length() <= MAX_ERROR_CHARS ? line : line.substring(0, MAX_ERROR_CHARS) + "…";
    }

    private static float[] unitLength(float[] vector) {
        double sum = 0;
        for (float x : vector) {
            sum += (double) x * x;
        }
        if (sum > 0) {
            float scale = (float) (1 / Math.sqrt(sum));
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= scale;
            }
        }
        return vector;
    }

    /** The prefix as used: none for blank, else ending in one space when it ended in none. */
    private static String prefix(String queryPrefix) {
        if (queryPrefix == null || queryPrefix.isBlank()) {
            return "";
        }
        String kept = queryPrefix.stripLeading();
        return Character.isWhitespace(kept.charAt(kept.length() - 1)) ? kept : kept + " ";
    }

    private static void requireText(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
    }

    private static void requirePositive(int value, String key) {
        if (value < 1) {
            throw new IllegalArgumentException(key + " must be at least 1, was " + value);
        }
    }

    /**
     * Settings for an engine: {@link LocalEmbeddingEngine#builder(String)} for the
     * defaults, {@link LocalEmbeddingEngine#toBuilder()} to start from an engine.
     */
    public static final class Builder {

        private final String baseUrl;
        private String model = DEFAULT_MODEL;
        private int dimensions = DEFAULT_DIMENSIONS;
        private boolean sendDimensions;
        private String queryPrefix = "";
        private int batchSize = DEFAULT_BATCH_SIZE;
        private boolean truncate;
        private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        private int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;

        private Builder(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /** The model, as Ollama knows it. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * The size every vector must have. Sent to Ollama only when it is asked for
         * ({@link #askedDimensions}).
         */
        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        /**
         * Asks Ollama for vectors of this size ({@code "dimensions"} on
         * {@code /api/embed}) and expects them; {@link LocalEmbeddingEngine#modelId()}
         * becomes {@code model@dimensions}. Null: nothing is asked, and the size
         * expected stays as it was.
         */
        public Builder askedDimensions(Integer dimensions) {
            if (dimensions != null) {
                this.dimensions = dimensions;
            }
            this.sendDimensions = dimensions != null;
            return this;
        }

        /** Put before every text {@link LocalEmbeddingEngine#embedQuery} embeds; null or blank for none. */
        public Builder queryPrefix(String queryPrefix) {
            this.queryPrefix = queryPrefix == null ? "" : queryPrefix;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder truncate(boolean truncate) {
            this.truncate = truncate;
            return this;
        }

        public Builder connectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        public Builder readTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
            return this;
        }

        /**
         * @throws IllegalArgumentException
         *             for a blank address or model, or a size, batch or timeout under 1
         */
        public LocalEmbeddingEngine build() {
            return new LocalEmbeddingEngine(baseUrl, model, dimensions, sendDimensions, queryPrefix, batchSize,
                    truncate, connectTimeoutMs, readTimeoutMs);
        }
    }

    /** {@code /api/embed}'s answer; package-private so Jackson can build it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbedResponse(float[][] embeddings) {
    }

    /** Internal signal: this Ollama predates {@code /api/embed}. */
    private static final class BatchEndpointMissing extends Exception {
        BatchEndpointMissing() {
            super(null, null, false, false);
        }
    }
}

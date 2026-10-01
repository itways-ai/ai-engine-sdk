package com.itways.assistant.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The engine against a real Ollama with {@code granite-embedding:278m} pulled
 * (and {@code snowflake-arctic-embed2} for the knowledge model's test).
 * Skipped unless asked for:
 *
 * <pre>
 * mvn test -Dtest=LocalEmbeddingEngineLiveTest -Dollama.live-url=http://127.0.0.1:11434
 * </pre>
 *
 * Only embedding calls; nothing is pulled, created or deleted.
 */
@EnabledIfSystemProperty(named = "ollama.live-url", matches = "https?://.+")
class LocalEmbeddingEngineLiveTest {

    private static final String URL = System.getProperty("ollama.live-url");

    @Test
    void fortyMixedTextsComeBackInOrderAsUnitVectors() {
        LocalEmbeddingEngine engine = new LocalEmbeddingEngine(URL);
        List<String> texts = IntStream.range(0, 40)
                .mapToObj(i -> i % 2 == 0 ? "Opening hours, question " + i : "متى تفتحون؟ سؤال رقم " + i)
                .toList();

        List<float[]> vectors = engine.embedBatch(texts);

        assertThat(vectors).hasSize(40).allSatisfy(vector -> {
            assertThat(vector).hasSize(768);
            assertThat(norm(vector)).isCloseTo(1.0, within(1e-3));
        });
        // Same text, same vector, wherever it sits in the batch.
        float[] alone = engine.embed(texts.get(37));
        assertThat(cosine(alone, vectors.get(37))).isCloseTo(1.0, within(1e-4));
        assertThat(cosine(alone, vectors.get(36))).isLessThan(0.999);
    }

    @Test
    void aRunLongerThanTheContextIsTypedUnlessTruncationIsOn() {
        String run = "word ".repeat(1_000).strip(); // ≈ 5 000 characters, over 512 tokens
        List<String> texts = List.of("short one", run, "another short one");

        assertThatThrownBy(() -> new LocalEmbeddingEngine(URL).embedBatch(texts))
                .isInstanceOfSatisfying(EmbeddingInputTooLongException.class,
                        e -> assertThat(e.getIndex()).isEqualTo(1));

        LocalEmbeddingEngine truncating = new LocalEmbeddingEngine(URL, LocalEmbeddingEngine.DEFAULT_MODEL, 768, 32,
                true, 5_000, 60_000);
        assertThat(truncating.embedBatch(texts)).hasSize(3);
    }

    /** Needs {@code snowflake-arctic-embed2} pulled too: the knowledge base's model (1.3.0). */
    @Test
    void theKnowledgeModelAtSevenSixtyEightWithTheQueryPrefix() {
        LocalEmbeddingEngine knowledge = new LocalEmbeddingEngine(URL).toBuilder().model("snowflake-arctic-embed2")
                .askedDimensions(768).queryPrefix("query: ").build();

        float[] query = knowledge.embedQuery("What are your opening hours?");
        float[] passage = knowledge.embed("We open every day from 9 am to 5 pm.");
        float[] prefixedByHand = knowledge.embed("query: What are your opening hours?");

        assertThat(knowledge.modelId()).isEqualTo("snowflake-arctic-embed2@768");
        assertThat(query).hasSize(768);
        assertThat(passage).hasSize(768);
        assertThat(norm(query)).isCloseTo(1.0, within(1e-3));
        assertThat(cosine(query, prefixedByHand)).isCloseTo(1.0, within(1e-4));
    }

    private static double norm(float[] vector) {
        return Math.sqrt(dot(vector, vector));
    }

    private static double cosine(float[] a, float[] b) {
        return dot(a, b) / (norm(a) * norm(b));
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }
}

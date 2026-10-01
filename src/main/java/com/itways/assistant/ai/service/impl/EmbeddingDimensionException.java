package com.itways.assistant.ai.service.impl;

/**
 * Ollama returned a vector whose size is not {@code ai.embedding.dimensions}.
 *
 * <p>
 * The vector columns have a fixed size (768 for {@code granite-embedding:278m}),
 * so such a vector cannot be stored or compared. It usually means
 * {@code ai.embedding.model} was changed without {@code ai.embedding.dimensions}.
 */
public class EmbeddingDimensionException extends EmbeddingException {

    private final int expected;
    private final int actual;

    public EmbeddingDimensionException(String model, int expected, int actual) {
        super("Model " + model + " returned a " + actual + "-dimensional vector, expected " + expected
                + " (ai.embedding.dimensions)");
        this.expected = expected;
        this.actual = actual;
    }

    public int getExpected() {
        return expected;
    }

    public int getActual() {
        return actual;
    }
}

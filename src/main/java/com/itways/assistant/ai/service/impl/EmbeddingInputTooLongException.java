package com.itways.assistant.ai.service.impl;

/**
 * A text is longer than the embedding model's context (512 tokens for
 * {@code granite-embedding:278m}, roughly 1 500 to 2 000 characters).
 *
 * <p>
 * Ollama refuses such a text with "the input length exceeds the context length"
 * (HTTP 400 from {@code /api/embed}, 500 from {@code /api/embeddings}) unless
 * {@code ai.embedding.truncate} is on, in which case Ollama embeds only the
 * beginning of the text and this exception does not occur.
 */
public class EmbeddingInputTooLongException extends EmbeddingException {

    private final int index;
    private final int length;

    /**
     * @param index
     *            the text's position in the list given to
     *            {@link LocalEmbeddingEngine#embedBatch}, 0 for
     *            {@link LocalEmbeddingEngine#embed}, -1 when Ollama refused a batch
     *            but accepted each of its texts on its own
     * @param length
     *            the text's length in characters, -1 when the index is unknown
     */
    public EmbeddingInputTooLongException(String model, int index, int length, String ollamaMessage,
            Throwable cause) {
        super(describe(model, index, length, ollamaMessage), cause);
        this.index = index;
        this.length = length;
    }

    /** The offending text's position in the caller's list, or -1 when unknown. */
    public int getIndex() {
        return index;
    }

    /** The offending text's length in characters, or -1 when unknown. */
    public int getLength() {
        return length;
    }

    private static String describe(String model, int index, int length, String ollamaMessage) {
        String which = index < 0 ? "A text" : "Text " + index + " (" + length + " characters)";
        return which + " is longer than the context of " + model + "; split or shorten it (Ollama: " + ollamaMessage
                + ")";
    }
}

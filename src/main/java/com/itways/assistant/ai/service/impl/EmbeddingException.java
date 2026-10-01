package com.itways.assistant.ai.service.impl;

/**
 * An embedding could not be produced: Ollama was unreachable, too slow, or
 * answered with an error or with something that is not a usable vector.
 *
 * <p>
 * The two subclasses name the failures a caller can act on:
 * {@link EmbeddingInputTooLongException} (shorten or split that text) and
 * {@link EmbeddingDimensionException} (the configured model does not match the
 * configured vector size). Anything else means "embedding is unavailable right
 * now". The message never contains the text that was being embedded.
 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}

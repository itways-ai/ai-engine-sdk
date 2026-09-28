package com.itways.assistant.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What a provider call produced: an answer, or an {@link #getError() error}.
 *
 * <p>
 * Never both. A failed call used to come back with the provider's error text
 * as {@code content} ("Claude API Error: 429 …"), which callers could not tell
 * from an answer; a user could hear it and a journey could branch on it. Now a
 * failure sets {@code error} and leaves {@code content} null, so code that only
 * reads {@code content} sees nothing rather than an error string. Check
 * {@link #isError()} first.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiResponse {
    /** The model's text. Null when {@link #isError()}, and may be null when the model only called tools. */
    private String content;
    private String model;
    private Usage usage;
    /**
     * Tools the model asked for instead of, or alongside, answering. When this
     * is non-empty the turn is not finished: run them, hand the results back
     * with {@link AiMessage#toolResult}, and ask again.
     */
    private java.util.List<AiToolCall> toolCalls;
    /**
     * Why there is no answer. Null on success. Named explicitly so the
     * {@code @JsonIgnore} on {@link #isError()} does not drop the whole property.
     */
    @JsonProperty("error")
    private AiError error;

    /** A failed call: no content, no tool calls, only the reason. */
    public static AiResponse failure(AiError error) {
        return AiResponse.builder().error(error).build();
    }

    /** True when the call failed; {@link #getError()} says why and {@code content} is null. */
    @JsonIgnore
    public boolean isError() {
        return error != null;
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Usage {
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
    }
}

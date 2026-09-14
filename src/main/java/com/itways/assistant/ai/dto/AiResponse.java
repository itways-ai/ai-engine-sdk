package com.itways.assistant.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiResponse {
    private String content;
    private String model;
    private Usage usage;
    /**
     * Tools the model asked for instead of, or alongside, answering. When this
     * is non-empty the turn is not finished: run them, hand the results back
     * with {@link AiMessage#toolResult}, and ask again.
     */
    private java.util.List<AiToolCall> toolCalls;

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

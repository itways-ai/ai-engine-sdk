package com.itways.assistant.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AiEmbeddingRequest extends BaseAiRequest {
    /**
     * The text to embed.
     */
    private String input;

    /**
     * Optional model override (e.g. "text-embedding-3-small").
     * If null, each agent uses its own default embedding model.
     */
    private String model;
}

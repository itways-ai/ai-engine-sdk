package com.itways.assistant.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiEmbeddingResponse {
    /**
     * The embedding vector returned by the AI provider.
     * Dimension depends on the model (e.g. 1536 for text-embedding-3-small).
     */
    private float[] vector;

    /**
     * The model used to generate the embedding.
     */
    private String model;
}

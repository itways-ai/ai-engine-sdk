package com.itways.assistant.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AiTranscriptionRequest extends BaseAiRequest {
    private byte[] audioData;
    private String filename;
    private String language;
    private String model;
}

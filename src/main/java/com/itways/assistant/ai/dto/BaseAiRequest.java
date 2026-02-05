package com.itways.assistant.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseAiRequest {
    private AiRequestConfig config;
    
    private Double temperature;
    private String systemPrompt;
}

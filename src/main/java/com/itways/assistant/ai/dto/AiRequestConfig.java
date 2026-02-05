package com.itways.assistant.ai.dto;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRequestConfig {
    private String apiKey;
    private String provider;
    private Map<String, Object> additionalInfo;
}

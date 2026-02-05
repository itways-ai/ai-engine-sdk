package com.itways.assistant.ai.dto;

import lombok.Data;
import java.util.Map;

@Data
public class IntentResult {
    private String intent;
    private double confidence;
    private Map<String, Object> entities;
    private String originalText;
    private String reasoning;
}

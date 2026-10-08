package com.itways.assistant.ai.dto;

import java.util.Map;
import lombok.Data;

@Data
public class IntentResult {
    private String intent;
    private double confidence;
    private Map<String, Object> entities;
    private String originalText;
    private String reasoning;
}

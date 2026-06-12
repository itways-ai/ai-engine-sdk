package com.itways.assistant.ai.service.impl;

import com.itways.assistant.ai.dto.AiEmbeddingRequest;
import com.itways.assistant.ai.dto.AiEmbeddingResponse;
import com.itways.assistant.ai.dto.BaseAiRequest;
import com.itways.assistant.ai.service.AiAgent;

import org.springframework.web.client.RestTemplate;

/**
 * Base abstract class for AI Agents providing common utility methods,
 * extracting redundant configurations like effective API Key resolution.
 */
public abstract class AbstractAiAgent implements AiAgent {

    protected final String defaultApiKey;
    protected final RestTemplate restTemplate;

    public AbstractAiAgent(String defaultApiKey, RestTemplate restTemplate) {
        this.defaultApiKey = defaultApiKey;
        this.restTemplate = restTemplate;
    }

    /**
     * Resolves the effective API key by prioritizing the override key specified
     * in the runtime request config. If none provided, falls back to the default
     * application key configured in the context.
     */
    protected String getEffectiveApiKey(BaseAiRequest request) {
        if (request != null && request.getConfig() != null) {
            String overrideKey = request.getConfig().getApiKey();
            if (overrideKey != null && !overrideKey.isEmpty()) {
                return overrideKey;
            }
        }
        return defaultApiKey != null ? defaultApiKey : "";
    }
}

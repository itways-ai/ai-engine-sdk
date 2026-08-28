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

    /**
     * Resolves the model to call, most specific first: the one named on the
     * request, then the one configured on the account, then the agent default.
     *
     * <p>The model itself lives on the concrete request type, so callers pass it
     * in. The account-level step is the one that used to be missing. An agent
     * default is a last resort, not a policy — providers retire models, and when
     * one is retired every account without an explicit model breaks at once.
     */
    protected String getEffectiveModel(String requestedModel, BaseAiRequest request, String agentDefault) {
        if (requestedModel != null && !requestedModel.isBlank()) {
            return requestedModel;
        }
        if (request != null && request.getConfig() != null) {
            String configured = request.getConfig().getModel();
            if (configured != null && !configured.isBlank()) {
                return configured;
            }
        }
        return agentDefault;
    }
}

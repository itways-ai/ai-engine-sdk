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

    /**
     * The model this account chose for its provider, e.g. "gemini-2.5-flash".
     *
     * <p>Agents fall back to their own DEFAULT_MODEL when this is null. It used to
     * be null always: the model is stored on the account and arrives in the
     * resolver's DTO, but there was nowhere to put it here, so it was dropped on
     * the way through. Configuring a model therefore did nothing, and when an
     * agent's hard-coded default was retired by the provider every LLM call for
     * that account failed with a 404 no amount of reconfiguring could fix.
     */
    private String model;

    private Map<String, Object> additionalInfo;
}

package com.itways.assistant.ai.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Matches when {@code ai.ollama.base-url} has text.
 *
 * <p>
 * Not {@code @ConditionalOnProperty}: that matches a blank value too, and a
 * deployment switches Ollama off by leaving the variable behind it empty
 * ({@code ai.ollama.base-url=${AI_OLLAMA_BASE_URL:}}).
 */
public class OllamaConfiguredCondition implements Condition {

    static final String BASE_URL = "ai.ollama.base-url";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return StringUtils.hasText(context.getEnvironment().getProperty(BASE_URL));
    }
}

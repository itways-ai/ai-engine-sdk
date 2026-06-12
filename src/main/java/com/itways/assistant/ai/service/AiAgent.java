package com.itways.assistant.ai.service;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiEmbeddingRequest;
import com.itways.assistant.ai.dto.AiEmbeddingResponse;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;

import java.util.List;

public interface AiAgent {
    /**
     * Sends a chat request to the AI provider.
     */
    AiResponse chat(AiChatRequest request);

    /**
     * Sends a transcription request to the AI provider.
     */
    AiResponse transcribe(AiTranscriptionRequest request);

    /**
     * Returns the provider name (e.g., "GROQ", "OPENAI").
     */
    String getProvider();
}

package com.itways.assistant.ai.service;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

	private final AiAgent defaultAiAgent;

	@Autowired
	@Qualifier("aiAgents")
	private final Map<String, AiAgent> aiAgents;

	private AiAgent getAgent(String provider) {
		if (provider != null && aiAgents.containsKey(provider.toUpperCase())) {
			return aiAgents.get(provider.toUpperCase());
		}
		return defaultAiAgent;
	}

	public AiResponse chat(AiChatRequest request) {
		String provider = request.getConfig() != null ? request.getConfig().getProvider() : null;
		AiAgent agent = getAgent(provider);
		log.info("Processing chat request using agent: {}", agent.getProvider());
		log.debug("Chat request payload size: {} messages", request.getMessages() != null ? request.getMessages().size() : 0);
		AiResponse response = agent.chat(request);
		log.info("Chat request completed successfully with agent: {}", agent.getProvider());
		return response;
	}

	public AiResponse transcribe(AiTranscriptionRequest request) {
		String provider = request.getConfig() != null ? request.getConfig().getProvider() : null;
		AiAgent agent = getAgent(provider);
		log.info("Processing transcription request using agent: {}", agent.getProvider());
		AiResponse response = agent.transcribe(request);
		log.info("Transcription request completed successfully with agent: {}", agent.getProvider());
		return response;
	}
}

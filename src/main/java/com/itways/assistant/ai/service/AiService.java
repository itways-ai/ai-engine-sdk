package com.itways.assistant.ai.service;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.itways.assistant.ai.config.AiAgent;
import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;

import lombok.RequiredArgsConstructor;

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
		return getAgent(provider).chat(request);
	}

	public AiResponse transcribe(AiTranscriptionRequest request) {
		String provider = request.getConfig() != null ? request.getConfig().getProvider() : null;
		return getAgent(provider).transcribe(request);
	}
}

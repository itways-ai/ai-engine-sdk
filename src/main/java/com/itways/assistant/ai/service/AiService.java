package com.itways.assistant.ai.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiEmbeddingRequest;
import com.itways.assistant.ai.dto.AiEmbeddingResponse;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

	@Autowired
	@Qualifier("aiAgents")
	private final Map<String, AiAgent> aiAgents;

//	private AiAgent getAgent(String provider) {
//		if (provider != null && aiAgents.containsKey(provider.toUpperCase())) {
//			return aiAgents.get(provider.toUpperCase());
//		}
//		return defaultAiAgent;
//	}

	public AiResponse chat(AiChatRequest request) {
		// determine provider from the config we injected in speech-service
		String provider = request.getConfig() != null ? request.getConfig().getProvider() : null;
		if(provider == null){
			throw new IllegalArgumentException("No AI provider specified in the Request Config");
		}
		AiAgent agent = aiAgents.get(provider.toUpperCase());
		if(agent == null){
			throw new IllegalArgumentException("provider " + provider + " is not supported");
		}
		log.info("Processing chat request using agent: {}", agent.getProvider());
		log.debug("Chat request payload size: {} messages", request.getMessages() != null ? request.getMessages().size() : 0);
		AiResponse response = agent.chat(request);
		log.info("Chat request completed successfully with agent: {}", agent.getProvider());
		return response;
	}

	public AiResponse transcribe(AiTranscriptionRequest request) {
		String provider = request.getConfig() != null ? request.getConfig().getProvider() : null;
		if(provider == null){
			throw new IllegalArgumentException("No AI provider specified in the Request Config");
		}
		AiAgent agent = aiAgents.get(provider.toUpperCase());
		if(agent == null){
			throw new IllegalArgumentException("provider " + provider + " is not supported");
		}
		log.info("Processing transcription request using agent: {}", agent.getProvider());
		AiResponse response = agent.transcribe(request);
		log.info("Transcription request completed successfully with agent: {}", agent.getProvider());
		return response;
	}

	public AiEmbeddingResponse embed(AiEmbeddingRequest request) {
		String provider = request.getConfig() != null ? request.getConfig().getProvider() : null;
		if (provider == null) {
			throw new IllegalArgumentException("No AI provider specified in the Request Config");
		}
		AiAgent agent = aiAgents.get(provider.toUpperCase());
		if (agent == null) {
			throw new IllegalArgumentException("provider " + provider + " is not supported");
		}
		log.info("Processing embedding request using agent: {}", agent.getProvider());
		AiEmbeddingResponse response = agent.embed(request);
		log.info("Embedding request completed successfully with agent: {}", agent.getProvider());
		return response;
	}

	public List<AiEmbeddingResponse> embedBatch(List<AiEmbeddingRequest> requests) {
		if(requests == null || requests.isEmpty()){
			return List.of();
		}

		// Resolve provider via config of the first token element
		var firstRequest = requests.get(0);
		String provider = firstRequest.getConfig() != null ? firstRequest.getConfig().getProvider() : null;
		if(provider == null){
			throw new IllegalArgumentException("No AI provider specified in the Request Config");
		}

		AiAgent agent = aiAgents.get(provider.toUpperCase());
		if(agent == null){
			throw new IllegalArgumentException("provider " + provider + " is not supported");
		}
		log.info("Processing collection batch embedding request using : {}", agent.getProvider());
		List<AiEmbeddingResponse> responses = agent.embedBatch(requests);
		log.info("Batch embedding generation completed successfully via agent model: {}", agent.getProvider());
		return responses;
	}
}

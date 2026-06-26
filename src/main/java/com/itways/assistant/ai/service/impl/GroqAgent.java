package com.itways.assistant.ai.service.impl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.itways.assistant.ai.dto.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Groq AI Agent implementation. Supports Chat completions (Llama) and Audio
 * transcription (Whisper). Configured with relaxed SSL to handle connection
 * issues in restrictive environments.
 */
@Slf4j
public class GroqAgent extends AbstractAiAgent {

	private static final String GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions";
	private static final String GROQ_TRANSCRIPTION_URL = "https://api.groq.com/openai/v1/audio/transcriptions";
	private static final String DEFAULT_CHAT_MODEL = "llama-3.3-70b-versatile";
	private static final String DEFAULT_WHISPER_MODEL = "whisper-large-v3";
	private static final int TIMEOUT_MS = 60000;

	public GroqAgent(String defaultApiKey, RestTemplate restTemplate) {
		super(defaultApiKey, restTemplate);
	}

	@Override
	public String getProvider() {
		return "GROQ";
	}

	@Override
	public AiResponse chat(AiChatRequest request) {
		String model = request.getModel() != null ? request.getModel() : DEFAULT_CHAT_MODEL;
		log.info("Processing chat request for Groq, model: {}", model);
		String apiKey = getEffectiveApiKey(request);
		if (apiKey.isEmpty()) {
			log.error("Groq API Key missing");
			return errorResponse("Groq API Key missing");
		}

		try {
			HttpEntity<Map<String, Object>> entity = new HttpEntity<>(createChatBody(request),
					createJsonHeaders(apiKey));
			ResponseEntity<Map> response = restTemplate.postForEntity(GROQ_CHAT_URL, entity, Map.class);
			log.debug("Groq chat API call successful");
			return parseChatResponse(response.getBody(), request.getModel());
		} catch (RestClientException e) {
			log.error("Error calling Groq Chat API", e);
			return errorResponse("Groq API Error: " + e.getMessage());
		}
	}

	@Override
	public AiResponse transcribe(AiTranscriptionRequest request) {
		String model = getOrDefault(request.getModel(), DEFAULT_WHISPER_MODEL);
		log.info("Processing transcription request for Groq, model: {}", model);
		String apiKey = getEffectiveApiKey(request);
		if (apiKey.isEmpty()) {
			log.error("Groq API Key missing");
			return errorResponse("Groq API Key missing");
		}

		try {
			HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(createTranscriptionBody(request),
					createMultipartHeaders(apiKey));
			ResponseEntity<GroqResponse> response = restTemplate.postForEntity(GROQ_TRANSCRIPTION_URL, entity,
					GroqResponse.class);
			log.debug("Groq transcription API call successful");
			return parseTranscriptionResponse(response.getBody(), request.getModel());
		} catch (RestClientException e) {
			log.error("Error calling Groq Transcription API", e);
			return errorResponse("Error calling Groq: " + e.getMessage());
		}
	}

	// -------------------------------------------------------------------------
	// Helper Methods
	// -------------------------------------------------------------------------

	private HttpHeaders createJsonHeaders(String apiKey) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(apiKey);
		return headers;
	}

	private HttpHeaders createMultipartHeaders(String apiKey) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		headers.setBearerAuth(apiKey);
		return headers;
	}

	private Map<String, Object> createChatBody(AiChatRequest request) {
		Map<String, Object> body = new HashMap<>();

		boolean hasImages = request.getFiles() != null && request.getFiles().stream()
				.anyMatch(f -> f.getMimeType() != null && f.getMimeType().startsWith("image/"));

		String model = request.getModel();
		if (model == null || model.isEmpty() || DEFAULT_CHAT_MODEL.equals(model)) {
			// Switch to a vision model if images are present and no specific model was
			// requested
			model = hasImages ? "meta-llama/llama-4-scout-17b-16e-instruct" : DEFAULT_CHAT_MODEL;
		}

		body.put("model", model);
		body.put("temperature", getOrDefault(request.getTemperature(), 0.1));
		if (request.getMaxTokens() != null) {
			body.put("max_tokens", request.getMaxTokens());
		}

		body.put("messages", buildMessages(request));
		return body;
	}

	private List<Object> buildMessages(AiChatRequest request) {
		List<AiMessage> originalMessages = request.getMessages();
		List<AiWrappedFile> files = request.getFiles();
		boolean hasFiles = files != null && !files.isEmpty();

		List<Object> messages = new ArrayList<>();
		for (int i = 0; i < originalMessages.size(); i++) {
			AiMessage m = originalMessages.get(i);
			boolean isLastUserMessage = (i == originalMessages.size() - 1) && "user".equalsIgnoreCase(m.getRole());

			if (hasFiles && isLastUserMessage) {
				messages.add(Map.of("role", m.getRole(), "content", buildContentWithFiles(m.getContent(), files)));
			} else {
				messages.add(Map.of("role", m.getRole(), "content", m.getContent()));
			}
		}
		return messages;
	}

	private List<Map<String, Object>> buildContentWithFiles(String textContent, List<AiWrappedFile> files) {
		List<Map<String, Object>> contentParts = new ArrayList<>();

		// 1. Add Text
		if (textContent != null && !textContent.isEmpty()) {
			contentParts.add(Map.of("type", "text", "text", textContent));
		}

		// 2. Add Files
		for (AiWrappedFile file : files) {
			String mimeType = file.getMimeType() != null ? file.getMimeType() : "application/octet-stream";

			if (mimeType.startsWith("image/")) {
				// Handle Image (Base64 Data URL)
				String base64Content = Base64.getEncoder().encodeToString(file.getContent());
				String dataUrl = "data:" + mimeType + ";base64," + base64Content;
				contentParts.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
			} else {
				// Handle Other Files: Inject filename as context anyway
				String fileHeader = String.format("\n[Attached File: %s]\n", file.getFilename());

				// Handle Text Files (Direct Content Injection)
				if (mimeType.startsWith("text/") || mimeType.contains("json") || mimeType.contains("xml")
						|| mimeType.contains("yaml") || mimeType.contains("script")
						|| "application/octet-stream".equals(mimeType)) {
					String fileContent = new String(file.getContent(), StandardCharsets.UTF_8);
					String injectedText = String.format("%s%s\n", fileHeader, fileContent);
					contentParts.add(Map.of("type", "text", "text", injectedText));
				} else {
					// For other binary types (like PDF), AI can't read them directly yet,
					// but knowing the file is there helps it understand user references.
					contentParts.add(Map.of("type", "text", "text", fileHeader));
					log.warn("Skipping content injection for binary file: {} ({})", file.getFilename(), mimeType);
				}
			}
		}
		return contentParts;
	}

	private AiResponse parseChatResponse(Map<?, ?> responseBody, String requestedModel) {
		if (responseBody == null)
			return emptyResponse();

		List<?> choices = (List<?>) responseBody.get("choices");
		if (choices == null || choices.isEmpty())
			return emptyResponse();

		Map<?, ?> firstChoice = (Map<?, ?>) choices.get(0);
		Map<?, ?> message = (Map<?, ?>) firstChoice.get("message");
		String content = (String) message.get("content");

		return AiResponse.builder().content(content).model((String) responseBody.get("model"))
				.usage(parseUsage((Map<?, ?>) responseBody.get("usage"))).build();
	}

	private AiResponse.Usage parseUsage(Map<?, ?> usageMap) {
		if (usageMap == null)
			return null;
		return new AiResponse.Usage((Integer) usageMap.get("prompt_tokens"),
				(Integer) usageMap.get("completion_tokens"), (Integer) usageMap.get("total_tokens"));
	}

	private MultiValueMap<String, Object> createTranscriptionBody(AiTranscriptionRequest request) {
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", new ByteArrayResource(request.getAudioData()) {
			@Override
			public String getFilename() {
				return request.getFilename();
			}
		});
		body.add("model", getOrDefault(request.getModel(), DEFAULT_WHISPER_MODEL));

		if (request.getLanguage() != null && !request.getLanguage().equalsIgnoreCase("auto")) {
			// Extract ISO code (e.g., "en-US" -> "en")
			String isoLang = request.getLanguage().contains("-") ? request.getLanguage().split("-")[0]
					: request.getLanguage();
			body.add("language", isoLang);
		}
		return body;
	}

	private AiResponse parseTranscriptionResponse(GroqResponse response, String requestedModel) {
		if (response == null)
			return errorResponse("No response from Groq");
		return AiResponse.builder().content(response.getText())
				.model(getOrDefault(requestedModel, DEFAULT_WHISPER_MODEL)).build();
	}

	private AiResponse errorResponse(String message) {
		return AiResponse.builder().content(message).build();
	}

	private AiResponse emptyResponse() {
		return AiResponse.builder().content("").build();
	}

	private <T> T getOrDefault(T value, T defaultValue) {
		return value != null ? value : defaultValue;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	private static class GroqResponse {
		private String text;
	}
}

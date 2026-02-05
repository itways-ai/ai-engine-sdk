package com.itways.assistant.ai.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.itways.assistant.ai.config.AiAgent;
import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GroqAgent implements AiAgent {

	private final String defaultApiKey;
	private final RestTemplate restTemplate = new RestTemplate();

	private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
	private static final String GROQ_SPEECH_URL = "https://api.groq.com/openai/v1/audio/transcriptions";
	private static final String DEFAULT_CHAT_MODEL = "llama-3.3-70b-versatile";
	private static final String DEFAULT_WHISPER_MODEL = "whisper-large-v3";

	@Override
	public String getProvider() {
		return "GROQ";
	}

	@Override
	public AiResponse chat(AiChatRequest request) {
		String effectiveApiKey = (request.getConfig() != null && request.getConfig().getApiKey() != null)
				? request.getConfig().getApiKey()
				: defaultApiKey;
		if (effectiveApiKey == null || effectiveApiKey.isEmpty()) {
			return AiResponse.builder().content("Error: Groq API Key missing").build();
		}

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(effectiveApiKey);

		Map<String, Object> body = new HashMap<>();
		body.put("model", request.getModel() != null ? request.getModel() : DEFAULT_CHAT_MODEL);
		body.put("messages", request.getMessages().stream()
				.map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
				.collect(Collectors.toList()));
		body.put("temperature", request.getTemperature() != null ? request.getTemperature() : 0.1);
		if (request.getMaxTokens() != null) {
			body.put("max_tokens", request.getMaxTokens());
		}

		HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
		try {
			ResponseEntity<Map> response = restTemplate.postForEntity(GROQ_URL, entity, Map.class);
			Map<String, Object> responseBody = response.getBody();
			if (responseBody != null) {
				List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
				if (choices != null && !choices.isEmpty()) {
					Map<String, Object> messageObj = (Map<String, Object>) choices.get(0).get("message");
					String content = (String) messageObj.get("content");

					Map<String, Object> usageMap = (Map<String, Object>) responseBody.get("usage");
					AiResponse.Usage usage = null;
					if (usageMap != null) {
						usage = new AiResponse.Usage(
								(Integer) usageMap.get("prompt_tokens"),
								(Integer) usageMap.get("completion_tokens"),
								(Integer) usageMap.get("total_tokens"));
					}

					return AiResponse.builder()
							.content(content)
							.model((String) responseBody.get("model"))
							.usage(usage)
							.build();
				}
			}
		} catch (Exception e) {
			return AiResponse.builder().content("Groq API Error: " + e.getMessage()).build();
		}
		return AiResponse.builder().content("").build();
	}

	@Override
	public AiResponse transcribe(AiTranscriptionRequest request) {
		String effectiveApiKey = (request.getConfig() != null && request.getConfig().getApiKey() != null)
				? request.getConfig().getApiKey()
				: defaultApiKey;
		if (effectiveApiKey == null || effectiveApiKey.isEmpty()) {
			return AiResponse.builder().content("Error: Groq API Key missing").build();
		}

		try {
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.MULTIPART_FORM_DATA);
			headers.setBearerAuth(effectiveApiKey);

			MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
			body.add("file", new ByteArrayResource(request.getAudioData()) {
				@Override
				public String getFilename() {
					return request.getFilename();
				}
			});
			body.add("model", request.getModel() != null ? request.getModel() : DEFAULT_WHISPER_MODEL);

			if (request.getLanguage() != null && !request.getLanguage().isEmpty()
					&& !request.getLanguage().equalsIgnoreCase("auto")) {
				String isoLang = request.getLanguage().contains("-") ? request.getLanguage().split("-")[0]
						: request.getLanguage();
				body.add("language", isoLang);
			}

			HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

			ResponseEntity<GroqResponse> response = restTemplate.postForEntity(GROQ_SPEECH_URL, requestEntity,
					GroqResponse.class);
			if (response.getBody() != null) {
				return AiResponse.builder()
						.content(response.getBody().getText())
						.model(request.getModel() != null ? request.getModel() : DEFAULT_WHISPER_MODEL)
						.build();
			}
			return AiResponse.builder().content("No response from Groq").build();

		} catch (Exception e) {
			return AiResponse.builder().content("Error calling Groq: " + e.getMessage()).build();
		}
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	private static class GroqResponse {
		private String text;
	}
}

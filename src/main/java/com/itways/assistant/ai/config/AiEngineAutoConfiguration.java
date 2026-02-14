package com.itways.assistant.ai.config;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.itways.assistant.ai.impl.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@ComponentScan("com.itways.assistant.ai")
public class AiEngineAutoConfiguration {

	@Value("${ai.groq.api-key:}")
	private String groqApiKey;

	@Value("${ai.openai.api-key:}")
	private String openAiApiKey;

	@Value("${ai.claude.api-key:}")
	private String claudeApiKey;

	@Value("${ai.gemini.api-key:}")
	private String geminiApiKey;

	@Value("${ai.mistral.api-key:}")
	private String mistralApiKey;

	@Value("${ai.ollama.base-url:http://localhost:11434}")
	private String ollamaBaseUrl;

	@Value("${ai.active-provider:GROQ}")
	private String activeProvider;

	@PostConstruct
	public void print() {
		log.info("✅ AI SDK configuration initialized");
	}

	@Bean
	public GroqAgent groqAgent() {
		return new GroqAgent(groqApiKey);
	}

	@Bean
	public OpenAiAgent openAiAgent() {
		return new OpenAiAgent(openAiApiKey);
	}

	@Bean
	public AnthropicAgent claudeAgent() {
		return new AnthropicAgent(claudeApiKey);
	}

	@Bean
	public GeminiAgent geminiAgent() {
		return new GeminiAgent(geminiApiKey);
	}

	@Bean
	public MistralAgent mistralAgent() {
		return new MistralAgent(mistralApiKey);
	}

	@Bean
	public OllamaAgent ollamaAgent() {
		return new OllamaAgent(ollamaBaseUrl);
	}

	@Bean
	public Map<String, AiAgent> aiAgents(List<AiAgent> agentList) {
		return agentList.stream().collect(
				Collectors.toMap(AiAgent::getProvider, Function.identity(), (existing, replacement) -> existing));
	}

	@Bean
	@Primary
	public AiAgent activeAiAgent(Map<String, AiAgent> agents) {
		return agents.getOrDefault(activeProvider.toUpperCase(), agents.values().iterator().next());
	}
}

//package com.itways.assistant.ai.config;
//
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.context.annotation.Primary;
//
//import com.itways.assistant.ai.dto.AiRequestConfig;
//
//import lombok.RequiredArgsConstructor;
//
//@Configuration
//@RequiredArgsConstructor
//public class AiConfig {
//
//	@Bean
//	@Primary // 👈 THIS is important
//	public AiRequestConfig activeAiRequestConfig(AiProviderProperties props) {
//
//		String provider = props.getActiveProvider().toUpperCase();
//		String apiKey = props.getApiKeyForActiveProvider();
//
//		if (apiKey == null || apiKey.isBlank()) {
//			throw new IllegalStateException("No API key configured for active AI provider: " + provider);
//		}
//
//		return AiRequestConfig.builder().provider(provider).apiKey(apiKey).build();
//	}
//}

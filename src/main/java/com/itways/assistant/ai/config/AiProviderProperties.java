//package com.itways.assistant.ai.config;
//
//import java.util.HashMap;
//import java.util.Map;
//
//import org.springframework.boot.context.properties.ConfigurationProperties;
//import org.springframework.context.annotation.Configuration;
//
//import lombok.Getter;
//import lombok.Setter;
//
//@Configuration
//@ConfigurationProperties(prefix = "ai")
//@Getter
//@Setter
//public class AiProviderProperties {
//
//	private String activeProvider;
//
//	private Map<String, String> apiKeys = new HashMap<>();
//
//	public String getApiKeyForActiveProvider() {
//		return apiKeys.get(activeProvider.toLowerCase());
//	}
//}

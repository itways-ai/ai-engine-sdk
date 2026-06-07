package com.itways.assistant.ai.config;

import com.itways.assistant.ai.service.AiAgent;
import com.itways.assistant.ai.service.impl.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.ssl.SSLContexts;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.net.ssl.SSLContext;

@Slf4j
@Configuration
@ComponentScan("com.itways.assistant.ai")
public class AiEngineAutoConfiguration {

	@PostConstruct
	public void print() {
		log.info("✅ AI SDK configuration initialized");
	}

	@Bean
	public RestTemplate aiRestTemplate() {
		try {
			// Trust all certificates to avoid SSL/TLS handshake issues behind corporate
			// firewalls
			SSLContext sslContext = SSLContexts.custom()
					.loadTrustMaterial(null, (chain, authType) -> true).build();

			// Create HttpClient with relaxed SSL
			CloseableHttpClient httpClient = HttpClients
					.custom()
					.setConnectionManager(PoolingHttpClientConnectionManagerBuilder
							.create()
							.setSSLSocketFactory(new SSLConnectionSocketFactory(
									sslContext, NoopHostnameVerifier.INSTANCE))
							.build())
					.build();

			HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(
					httpClient);
			factory.setConnectTimeout(60000);
			return new RestTemplate(factory);
		} catch (Exception e) {
			log.error("Failed to configure relaxed SSL for RestTemplate. Falling back to default RestTemplate.", e);
			return new RestTemplate();
		}
		// @Bean
		// public RestTemplate aiRestTemplate() {
		// return new RestTemplate();
		// }
	}

	@Bean
	public GroqAgent groqAgent() {
		return new GroqAgent(null, aiRestTemplate());
	}

	@Bean
	public OpenAiAgent openAiAgent() {
		return new OpenAiAgent(null, aiRestTemplate());
	}

	@Bean
	public AnthropicAgent claudeAgent() {
		return new AnthropicAgent(null, aiRestTemplate());
	}

	@Bean
	public GeminiAgent geminiAgent() {
		return new GeminiAgent(null, aiRestTemplate());
	}

	@Bean
	public MistralAgent mistralAgent() {
		return new MistralAgent(null, aiRestTemplate());
	}

	@Bean
	public Map<String, AiAgent> aiAgents(List<AiAgent> agentList) {
		return agentList.stream().collect(
				Collectors.toMap(AiAgent::getProvider, Function.identity(), (existing, replacement) -> existing));
	}
}

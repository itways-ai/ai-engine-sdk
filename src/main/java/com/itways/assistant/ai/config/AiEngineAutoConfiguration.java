package com.itways.assistant.ai.config;

import com.itways.assistant.ai.service.AiAgent;
import com.itways.assistant.ai.service.impl.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;
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

			// Every timeout below has to be set explicitly, because the defaults are
			// "wait forever" and a provider that stalls then wedges the service.
			//
			// Seen in practice: one request to Groq never returned, and because a
			// socket with no read timeout never gives its pooled connection back,
			// and the pool defaults to two connections per route, the next two
			// requests queued behind it — with no connection-request timeout, also
			// forever. From the user's side the assistant simply stopped answering,
			// with nothing in the log after "Processing chat request".
			ConnectionConfig connectionConfig = ConnectionConfig.custom()
					.setConnectTimeout(Timeout.ofSeconds(15))
					.setSocketTimeout(Timeout.ofSeconds(120))
					.build();

			// 2 per route is the Apache default and far too low for a service that
			// makes an AI call per conversation turn: every provider is one route,
			// so two concurrent users saturate it.
			PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder
					.create()
					.setSSLSocketFactory(new SSLConnectionSocketFactory(
							sslContext, NoopHostnameVerifier.INSTANCE))
					.setDefaultConnectionConfig(connectionConfig)
					.setMaxConnTotal(50)
					.setMaxConnPerRoute(20)
					.build();

			RequestConfig requestConfig = RequestConfig.custom()
					// Fail fast when the pool is exhausted rather than queueing: a
					// caller that is told "busy" can fall back, one that is left
					// hanging cannot.
					.setConnectionRequestTimeout(Timeout.ofSeconds(10))
					// Generous, because a long completion legitimately takes a while,
					// but bounded so a stall is an error and not a hang.
					.setResponseTimeout(Timeout.ofSeconds(120))
					.build();

			CloseableHttpClient httpClient = HttpClients
					.custom()
					.setConnectionManager(connectionManager)
					.setDefaultRequestConfig(requestConfig)
					// Reap connections the far end dropped without telling us, which
					// would otherwise sit in the pool looking usable.
					.evictIdleConnections(Timeout.ofMinutes(5))
					.evictExpiredConnections()
					.build();

			return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
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

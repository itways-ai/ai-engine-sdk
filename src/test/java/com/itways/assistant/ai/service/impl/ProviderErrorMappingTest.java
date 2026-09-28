package com.itways.assistant.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.client.RestTemplate;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiError;
import com.itways.assistant.ai.dto.AiError.Kind;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiRequestConfig;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;
import com.sun.net.httpserver.HttpServer;

/**
 * Every agent against a stand-in provider on a loopback {@link HttpServer}.
 *
 * <p>
 * Unlike the per-agent tests, the request goes through a real HTTP client and
 * socket, so a timeout and a refused connection are the real exceptions. The
 * agents' provider URLs are constants; an interceptor sends the same path to
 * the stand-in instead. Whatever the provider says, a failure must come back
 * as an {@link AiError} of the right kind, with {@code content} null and the
 * key nowhere in it.
 */
class ProviderErrorMappingTest {

	private static final String KEY = "sk-live-0123456789abcdef";

	enum Provider {
		CLAUDE(AnthropicAgent::new, """
				{"model":"claude-opus-5","stop_reason":"refusal",
				 "stop_details":{"type":"refusal","category":"cyber"},
				 "content":[],"usage":{"input_tokens":5,"output_tokens":0}}
				""", "cyber"),
		OPENAI(OpenAiAgent::new, """
				{"model":"gpt-4o","choices":[{"finish_reason":"stop",
				 "message":{"role":"assistant","content":null,"refusal":"I can't help with that."}}]}
				""", "refusal"),
		GROQ(GroqAgent::new, """
				{"model":"openai/gpt-oss-120b","choices":[{"finish_reason":"content_filter",
				 "message":{"role":"assistant","content":""}}]}
				""", "content_filter"),
		GEMINI(GeminiAgent::new, """
				{"promptFeedback":{"blockReason":"SAFETY"},
				 "usageMetadata":{"promptTokenCount":4,"totalTokenCount":4}}
				""", "SAFETY"),
		MISTRAL(MistralAgent::new, """
				{"model":"mistral-small-2506","choices":[{"finish_reason":"content_filter",
				 "message":{"role":"assistant","content":null}}]}
				""", "content_filter");

		final BiFunction<String, RestTemplate, AbstractAiAgent> factory;
		final String refusalBody;
		final String refusalCategory;

		Provider(BiFunction<String, RestTemplate, AbstractAiAgent> factory, String refusalBody,
				String refusalCategory) {
			this.factory = factory;
			this.refusalBody = refusalBody;
			this.refusalCategory = refusalCategory;
		}
	}

	private HttpServer server;
	private ExecutorService handlers;
	private volatile int status;
	private volatile String body;
	private volatile long delayMs;
	private volatile int port;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		handlers = Executors.newCachedThreadPool();
		server.setExecutor(handlers);
		server.createContext("/", exchange -> {
			try {
				if (delayMs > 0) {
					Thread.sleep(delayMs);
				}
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(status, bytes.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(bytes);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (IOException clientGone) {
				// The timeout test hangs up first.
			} finally {
				exchange.close();
			}
		});
		server.start();
		port = server.getAddress().getPort();
	}

	@AfterEach
	void stop() {
		server.stop(0);
		handlers.shutdownNow();
	}

	/**
	 * The production client (httpclient5, as {@code aiRestTemplate} builds it),
	 * with short timeouts, sending every request to {@link #port} with its path
	 * unchanged.
	 */
	private RestTemplate stubbedTemplate(int readTimeoutMs) {
		PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
				.setDefaultConnectionConfig(ConnectionConfig.custom()
						.setConnectTimeout(Timeout.ofSeconds(1))
						.setSocketTimeout(Timeout.ofMilliseconds(readTimeoutMs))
						.build())
				.build();
		CloseableHttpClient client = HttpClients.custom()
				.setConnectionManager(connections)
				.setDefaultRequestConfig(RequestConfig.custom()
						.setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs)).build())
				.build();
		RestTemplate rest = new RestTemplate(new HttpComponentsClientHttpRequestFactory(client));
		rest.getInterceptors().add((request, payload, execution) -> execution.execute(new HttpRequestWrapper(request) {
			@Override
			public URI getURI() {
				URI original = request.getURI();
				return URI.create("http://127.0.0.1:" + port + original.getRawPath()
						+ (original.getRawQuery() == null ? "" : "?" + original.getRawQuery()));
			}
		}, payload));
		return rest;
	}

	private void respond(int status, String body) {
		this.status = status;
		this.body = body;
	}

	private static AiResponse chat(Provider provider, RestTemplate rest) {
		AbstractAiAgent agent = provider.factory.apply(null, rest);
		return agent.chat(AiChatRequest.builder()
				.config(AiRequestConfig.builder().provider(provider.name()).apiKey(KEY).build())
				.messages(List.of(AiMessage.user("Hello")))
				.build());
	}

	private static AiError failed(AiResponse response, Kind kind, Provider provider) {
		assertThat(response.isError()).as("isError").isTrue();
		assertThat(response.getContent()).as("content").isNull();
		assertThat(response.hasToolCalls()).isFalse();
		AiError error = response.getError();
		assertThat(error.getKind()).isEqualTo(kind);
		assertThat(error.getProvider()).isEqualTo(provider.name());
		assertThat(error.toString()).doesNotContain(KEY).doesNotContain("0123456789abcdef");
		return error;
	}

	@ParameterizedTest
	@EnumSource(Provider.class)
	void rateLimitIsRateLimitedWithTheProvidersMessageAndNoKey(Provider provider) {
		// OpenAI-style bodies quote the key they were given; it must not survive.
		respond(429, "{\"error\":{\"message\":\"Rate limit reached for key " + KEY
				+ " on tokens per min\",\"type\":\"tokens\"}}");

		AiError error = failed(chat(provider, stubbedTemplate(2000)), Kind.RATE_LIMITED, provider);

		assertThat(error.getProviderStatus()).isEqualTo(429);
		assertThat(error.getMessage()).startsWith("Rate limit reached for key [redacted]");
		assertThat(error.isRetryable()).isTrue();
		assertThat(error.summary()).isEqualTo("RATE_LIMITED (" + provider.name() + " 429)");
	}

	@ParameterizedTest
	@EnumSource(Provider.class)
	void rejectedKeyIsAuth(Provider provider) {
		respond(401, "{\"error\":{\"message\":\"Incorrect API key provided: sk-live-****cdef\"}}");

		AiError error = failed(chat(provider, stubbedTemplate(2000)), Kind.AUTH, provider);

		assertThat(error.getProviderStatus()).isEqualTo(401);
		assertThat(error.getMessage()).isEqualTo("Incorrect API key provided: [redacted]");
		assertThat(error.isRetryable()).isFalse();
	}

	@ParameterizedTest
	@EnumSource(Provider.class)
	void refusalIsRefusedWithTheProvidersCategory(Provider provider) {
		respond(200, provider.refusalBody);

		AiError error = failed(chat(provider, stubbedTemplate(2000)), Kind.REFUSED, provider);

		assertThat(error.getCategory()).isEqualTo(provider.refusalCategory);
		assertThat(error.getProviderStatus()).isNull();
		assertThat(error.isRetryable()).isFalse();
	}

	@ParameterizedTest
	@EnumSource(Provider.class)
	void serverErrorIsUnavailable(Provider provider) {
		for (int code : new int[] { 500, 502, 503, 529 }) {
			respond(code, "<html>upstream trouble</html>");

			AiError error = failed(chat(provider, stubbedTemplate(2000)), Kind.UNAVAILABLE, provider);

			assertThat(error.getProviderStatus()).isEqualTo(code);
			assertThat(error.isRetryable()).isTrue();
		}
	}

	@ParameterizedTest
	@EnumSource(Provider.class)
	void badRequestIsBadRequest(Provider provider) {
		respond(400, "{\"error\":{\"message\":\"temperature: not supported by this model\"}}");

		AiError error = failed(chat(provider, stubbedTemplate(2000)), Kind.BAD_REQUEST, provider);

		assertThat(error.getMessage()).isEqualTo("temperature: not supported by this model");
	}

	@ParameterizedTest
	@EnumSource(Provider.class)
	void aProviderThatDoesNotAnswerInTimeIsTimeout(Provider provider) {
		respond(200, "{}");
		delayMs = 1500;

		AiError error = failed(chat(provider, stubbedTemplate(200)), Kind.TIMEOUT, provider);

		assertThat(error.getProviderStatus()).isNull();
	}

	@ParameterizedTest
	@EnumSource(Provider.class)
	void aProviderThatCannotBeReachedIsUnavailable(Provider provider) throws IOException {
		try (ServerSocket free = new ServerSocket(0)) {
			port = free.getLocalPort();
		}
		// Nothing listens there now.

		AiError error = failed(chat(provider, stubbedTemplate(2000)), Kind.UNAVAILABLE, provider);

		assertThat(error.getProviderStatus()).isNull();
	}

	@ParameterizedTest
	@EnumSource(Provider.class)
	void anEmptyAnswerIsAnErrorNotABlankReply(Provider provider) {
		respond(200, "{}");

		failed(chat(provider, stubbedTemplate(2000)), Kind.OTHER, provider);
	}

	@Test
	void geminiWithheldMidAnswerIsRefused() {
		respond(200, """
				{"candidates":[{"finishReason":"SAFETY","index":0}]}
				""");

		AiError error = failed(chat(Provider.GEMINI, stubbedTemplate(2000)), Kind.REFUSED, Provider.GEMINI);

		assertThat(error.getCategory()).isEqualTo("SAFETY");
	}

	@Test
	void geminiBadKeyIsAuthDespiteThe400() {
		respond(400, """
				{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.",
				 "status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}
				""");

		AiError error = failed(chat(Provider.GEMINI, stubbedTemplate(2000)), Kind.AUTH, Provider.GEMINI);

		assertThat(error.getProviderStatus()).isEqualTo(400);
	}

	@Test
	void contentFilteredAnswerThatStillHasTextIsAnAnswer() {
		respond(200, """
				{"model":"gpt-4o","choices":[{"finish_reason":"content_filter",
				 "message":{"role":"assistant","content":"Here is the part I can share."}}]}
				""");

		AiResponse response = chat(Provider.OPENAI, stubbedTemplate(2000));

		assertThat(response.isError()).isFalse();
		assertThat(response.getContent()).isEqualTo("Here is the part I can share.");
	}

	@Test
	void transcriptionFailuresAreErrorsToo() {
		respond(429, "{\"error\":{\"message\":\"slow down\"}}");
		for (Provider provider : new Provider[] { Provider.OPENAI, Provider.GROQ }) {
			AiResponse response = provider.factory.apply(null, stubbedTemplate(2000))
					.transcribe(AiTranscriptionRequest.builder()
							.config(AiRequestConfig.builder().provider(provider.name()).apiKey(KEY).build())
							.audioData(new byte[] { 1 }).filename("clip.ogg").build());

			AiError error = failed(response, Kind.RATE_LIMITED, provider);
			assertThat(error.getMessage()).isEqualTo("slow down");
		}
	}

	@Test
	void aFailedResponseSerializesItsErrorAndReadsBack() throws Exception {
		ObjectMapper json = new ObjectMapper();
		AiResponse failed = AiResponse.failure(AiError.builder().kind(Kind.RATE_LIMITED).provider("GROQ")
				.providerStatus(429).message("slow down").build());

		String written = json.writeValueAsString(failed);
		AiResponse read = json.readValue(written, AiResponse.class);

		assertThat(written).contains("\"error\":{").contains("\"kind\":\"RATE_LIMITED\"")
				.doesNotContain("retryable");
		assertThat(read.isError()).isTrue();
		assertThat(read.getError().getProviderStatus()).isEqualTo(429);
		assertThat(read.getContent()).isNull();
	}

	@Test
	void anAnswerIsNotAnError() {
		assertThat(AiResponse.builder().content("Hi").build().isError()).isFalse();
	}
}

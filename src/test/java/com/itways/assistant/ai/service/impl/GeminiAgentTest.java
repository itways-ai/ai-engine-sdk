package com.itways.assistant.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiError;
import com.itways.assistant.ai.dto.AiResponse;

class GeminiAgentTest extends AgentTestSupport {

	private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
	private static final String OK = """
			{"candidates":[{"content":{"role":"model","parts":[{"text":"Hi there"}]}}],
			 "usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":2,"totalTokenCount":5}}
			""";

	private final GeminiAgent agent = new GeminiAgent(null, rest);

	@Test
	void defaultRequestShapeKeepsTheKeyOutOfTheUrl() {
		server.expect(requestTo(BASE + "gemini-3.5-flash-lite:generateContent"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("x-goog-api-key", "g-test"))
				.andExpect(jsonPath("$.contents[0].role").value("user"))
				.andExpect(jsonPath("$.contents[0].parts[0].text").value("Hello"))
				.andExpect(jsonPath("$.generationConfig").doesNotExist())
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		AiResponse response = agent.chat(hello(config("GEMINI", "g-test", null)));

		server.verify();
		assertThat(response.getContent()).isEqualTo("Hi there");
		assertThat(response.getModel()).isEqualTo("gemini-3.5-flash-lite");
		assertThat(response.getUsage().getTotalTokens()).isEqualTo(5);
	}

	@Test
	void accountModelGoesInThePathAndAssistantBecomesModel() {
		server.expect(requestTo(BASE + "gemini-2.5-flash:generateContent"))
				.andExpect(jsonPath("$.contents[1].role").value("model"))
				.andExpect(jsonPath("$.contents[2].role").value("user"))
				.andExpect(jsonPath("$.generationConfig.temperature").value(0.4))
				.andExpect(jsonPath("$.generationConfig.maxOutputTokens").value(50))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		AiChatRequest request = AiChatRequest.builder().config(config("GEMINI", "g-test", "gemini-2.5-flash"))
				.messages(List.of(AiMessage.user("Hi"), AiMessage.assistant("Hello"), AiMessage.user("Bye")))
				.build();
		request.setTemperature(0.4);
		request.setMaxTokens(50);
		agent.chat(request);

		server.verify();
	}

	@Test
	void parsesFunctionCalls() {
		server.expect(requestTo(BASE + "gemini-3.5-flash-lite:generateContent")).andRespond(withSuccess("""
				{"candidates":[{"content":{"parts":[{"functionCall":{"name":"lookup","args":{"q":"Irbid"}}}]}}]}
				""", MediaType.APPLICATION_JSON));

		AiResponse response = agent.chat(hello(config("GEMINI", "g-test", null)));

		assertThat(response.getContent()).isNull();
		assertThat(response.getToolCalls()).singleElement().satisfies(call -> {
			assertThat(call.getId()).isEqualTo("call_0");
			assertThat(call.argument("q")).isEqualTo("Irbid");
		});
	}

	@ParameterizedTest
	@MethodSource("com.itways.assistant.ai.service.impl.AgentTestSupport#providerFailures")
	void providerFailuresBecomeAnErrorReplyThatDoesNotCarryTheKey(HttpStatus status) {
		server.expect(requestTo(BASE + "gemini-3.5-flash-lite:generateContent")).andRespond(withStatus(status));

		AiResponse response = agent.chat(hello(config("GEMINI", "g-secret-key", null)));

		AiError error = assertFailure(response, kindOf(status));
		assertThat(error.getProviderStatus()).isEqualTo(status.value());
		assertThat(error.toString()).doesNotContain("g-secret-key");
	}
}

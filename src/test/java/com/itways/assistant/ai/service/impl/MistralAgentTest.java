package com.itways.assistant.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiError;
import com.itways.assistant.ai.dto.AiResponse;

class MistralAgentTest extends AgentTestSupport {

	private static final String URL = "https://api.mistral.ai/v1/chat/completions";
	private static final String OK = """
			{"model":"mistral-small-2506",
			 "choices":[{"message":{"role":"assistant","content":"Hi there"}}],
			 "usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}
			""";

	private final MistralAgent agent = new MistralAgent(null, rest);

	@Test
	void defaultRequestShape() {
		server.expect(requestTo(URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Bearer m-test"))
				.andExpect(jsonPath("$.model").value("mistral-small-2506"))
				.andExpect(jsonPath("$.temperature").doesNotExist())
				.andExpect(jsonPath("$.max_tokens").doesNotExist())
				.andExpect(jsonPath("$.messages[0].content").value("Hello"))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		AiResponse response = agent.chat(hello(config("MISTRAL", "m-test", null)));

		server.verify();
		assertThat(response.getContent()).isEqualTo("Hi there");
		assertThat(response.getUsage().getPromptTokens()).isEqualTo(4);
	}

	@Test
	void accountModelAndSamplingOverrides() {
		server.expect(requestTo(URL))
				.andExpect(jsonPath("$.model").value("ministral-14b-2512"))
				.andExpect(jsonPath("$.temperature").value(0.9))
				.andExpect(jsonPath("$.max_tokens").value(20))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		AiChatRequest request = hello(config("MISTRAL", "m-test", "ministral-14b-2512"));
		request.setTemperature(0.9);
		request.setMaxTokens(20);
		agent.chat(request);

		server.verify();
	}

	@ParameterizedTest
	@MethodSource("com.itways.assistant.ai.service.impl.AgentTestSupport#providerFailures")
	void providerFailuresBecomeAnErrorReply(HttpStatus status) {
		server.expect(requestTo(URL)).andRespond(withStatus(status));

		AiResponse response = agent.chat(hello(config("MISTRAL", "m-test", null)));

		AiError error = assertFailure(response, kindOf(status));
		assertThat(error.getProvider()).isEqualTo("MISTRAL");
		assertThat(error.getProviderStatus()).isEqualTo(status.value());
	}

	@Test
	void transcriptionIsNotSupported() {
		AiError error = assertFailure(agent.transcribe(null), AiError.Kind.BAD_REQUEST);
		assertThat(error.getMessage()).contains("does not support audio transcription");
		server.verify();
	}
}

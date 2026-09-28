package com.itways.assistant.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiError;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTool;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;

class OpenAiAgentTest extends AgentTestSupport {

	private static final String URL = "https://api.openai.com/v1/chat/completions";
	private static final String OK = """
			{"model":"gpt-4o-2024-08-06",
			 "choices":[{"message":{"role":"assistant","content":"Hi there"}}],
			 "usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}}
			""";

	private final OpenAiAgent agent = new OpenAiAgent(null, rest);

	@Test
	void defaultRequestShape() {
		server.expect(requestTo(URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Bearer sk-test"))
				.andExpect(jsonPath("$.model").value("gpt-4o"))
				.andExpect(jsonPath("$.temperature").value(0.7))
				.andExpect(jsonPath("$.max_tokens").doesNotExist())
				.andExpect(jsonPath("$.tools").doesNotExist())
				.andExpect(jsonPath("$.messages[0].role").value("user"))
				.andExpect(jsonPath("$.messages[0].content").value("Hello"))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		AiResponse response = agent.chat(hello(config("OPENAI", "sk-test", null)));

		server.verify();
		assertThat(response.getContent()).isEqualTo("Hi there");
		assertThat(response.getModel()).isEqualTo("gpt-4o-2024-08-06");
		assertThat(response.getUsage().getTotalTokens()).isEqualTo(11);
	}

	@Test
	void accountModelAndRequestParametersOverrideDefaults() {
		server.expect(requestTo(URL))
				.andExpect(jsonPath("$.model").value("gpt-5-nano"))
				.andExpect(jsonPath("$.temperature").value(0.1))
				.andExpect(jsonPath("$.max_tokens").value(64))
				.andExpect(jsonPath("$.tools[0].type").value("function"))
				.andExpect(jsonPath("$.tools[0].function.name").value("lookup"))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		AiChatRequest request = hello(config("OPENAI", "sk-test", "gpt-5-nano"));
		request.setTemperature(0.1);
		request.setMaxTokens(64);
		request.setTools(List.of(AiTool.of("lookup", "Look something up")));
		agent.chat(request);

		server.verify();
	}

	@Test
	void parsesToolCalls() {
		server.expect(requestTo(URL)).andRespond(withSuccess("""
				{"model":"gpt-4o","choices":[{"message":{"role":"assistant","content":null,
				  "tool_calls":[{"id":"call_1","type":"function",
				    "function":{"name":"lookup","arguments":"{\\"q\\":\\"Irbid\\"}"}}]}}]}
				""", MediaType.APPLICATION_JSON));

		AiResponse response = agent.chat(hello(config("OPENAI", "sk-test", null)));

		assertThat(response.getContent()).isNull();
		assertThat(response.getToolCalls()).singleElement()
				.satisfies(call -> assertThat(call.getArguments()).isEqualTo(Map.of("q", "Irbid")));
	}

	@ParameterizedTest
	@MethodSource("com.itways.assistant.ai.service.impl.AgentTestSupport#providerFailures")
	void providerFailuresBecomeAnErrorReply(HttpStatus status) {
		server.expect(requestTo(URL)).andRespond(withStatus(status));

		AiResponse response = agent.chat(hello(config("OPENAI", "sk-test", null)));

		AiError error = assertFailure(response, kindOf(status));
		assertThat(error.getProviderStatus()).isEqualTo(status.value());
	}

	@Test
	void transcriptionIsAMultipartUploadWithTheWhisperDefault() {
		server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
				.andExpect(header("Authorization", "Bearer sk-test"))
				.andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
				.andExpect(request -> {
					assertThat(body(request)).contains("whisper-1").contains("name=\"language\"").contains("\r\nar\r\n")
							.contains("filename=\"clip.wav\"");
				})
				.andRespond(withSuccess("{\"text\":\"marhaba\"}", MediaType.APPLICATION_JSON));

		AiResponse response = agent.transcribe(AiTranscriptionRequest.builder()
				.config(config("OPENAI", "sk-test", null)).audioData(new byte[] { 1, 2, 3 })
				.filename("clip.wav").language("ar-JO").build());

		server.verify();
		assertThat(response.getContent()).isEqualTo("marhaba");
		assertThat(response.getModel()).isEqualTo("whisper-1");
	}

	@Test
	void missingApiKeyIsReportedWithoutCallingTheProvider() {
		AiError error = assertFailure(agent.chat(hello(config("OPENAI", "", null))), AiError.Kind.AUTH);
		assertThat(error.getMessage()).isEqualTo("OpenAI API key missing");
		assertThat(error.getProviderStatus()).isNull();
		server.verify();
	}
}

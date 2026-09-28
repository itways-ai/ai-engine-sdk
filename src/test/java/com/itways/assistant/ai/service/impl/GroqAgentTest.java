package com.itways.assistant.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
import com.itways.assistant.ai.dto.AiTranscriptionRequest;
import com.itways.assistant.ai.dto.AiWrappedFile;

class GroqAgentTest extends AgentTestSupport {

	private static final String URL = "https://api.groq.com/openai/v1/chat/completions";
	private static final String OK = """
			{"model":"openai/gpt-oss-120b",
			 "choices":[{"message":{"role":"assistant","content":"Hi there"}}],
			 "usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}
			""";
	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G' };

	private final GroqAgent agent = new GroqAgent(null, rest);

	@Test
	void defaultRequestShape() {
		server.expect(requestTo(URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Bearer gsk-test"))
				.andExpect(jsonPath("$.model").value("openai/gpt-oss-120b"))
				.andExpect(jsonPath("$.temperature").value(0.1))
				.andExpect(jsonPath("$.max_tokens").doesNotExist())
				.andExpect(jsonPath("$.messages[0].content").value("Hello"))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		AiResponse response = agent.chat(hello(config("GROQ", "gsk-test", null)));

		server.verify();
		assertThat(response.getContent()).isEqualTo("Hi there");
		assertThat(response.getModel()).isEqualTo("openai/gpt-oss-120b");
		assertThat(response.getUsage().getTotalTokens()).isEqualTo(6);
	}

	@Test
	void accountModelOverridesTheDefault() {
		server.expect(requestTo(URL))
				.andExpect(jsonPath("$.model").value("openai/gpt-oss-20b"))
				.andExpect(jsonPath("$.temperature").value(0.5))
				.andExpect(jsonPath("$.max_tokens").value(100))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		AiChatRequest request = hello(config("GROQ", "gsk-test", "openai/gpt-oss-20b"));
		request.setTemperature(0.5);
		request.setMaxTokens(100);
		agent.chat(request);

		server.verify();
	}

	@Test
	void imagesWithNoChosenModelGoToTheVisionModelAsDataUrls() {
		String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG);
		server.expect(requestTo(URL))
				.andExpect(jsonPath("$.model").value("qwen/qwen3.8-27b"))
				.andExpect(jsonPath("$.messages[0].role").value("user"))
				.andExpect(jsonPath("$.messages[0].content[0].type").value("text"))
				.andExpect(jsonPath("$.messages[0].content[0].text").value("What is on this receipt?"))
				.andExpect(jsonPath("$.messages[0].content[1].type").value("image_url"))
				.andExpect(jsonPath("$.messages[0].content[1].image_url.url").value(dataUrl))
				.andExpect(jsonPath("$.messages[0].content[2].type").value("text"))
				.andExpect(jsonPath("$.messages[0].content[2].text")
						.value("\n[Attached File: notes.txt]\nline one\n"))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		agent.chat(withImage(config("GROQ", "gsk-test", null)));

		server.verify();
	}

	@Test
	void visionModelIsConfigurable() {
		GroqAgent configured = new GroqAgent(null, rest, "some/other-vision-model");
		server.expect(requestTo(URL))
				.andExpect(jsonPath("$.model").value("some/other-vision-model"))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		configured.chat(withImage(config("GROQ", "gsk-test", null)));

		server.verify();
	}

	@Test
	void aModelTheAccountChoseIsKeptEvenWithImages() {
		server.expect(requestTo(URL))
				.andExpect(jsonPath("$.model").value("openai/gpt-oss-20b"))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		agent.chat(withImage(config("GROQ", "gsk-test", "openai/gpt-oss-20b")));

		server.verify();
	}

	@Test
	void parsesToolCalls() {
		server.expect(requestTo(URL)).andRespond(withSuccess("""
				{"model":"openai/gpt-oss-120b","choices":[{"message":{"role":"assistant","content":null,
				  "tool_calls":[{"id":"call_7","type":"function",
				    "function":{"name":"lookup","arguments":"{}"}}]}}]}
				""", MediaType.APPLICATION_JSON));

		AiResponse response = agent.chat(hello(config("GROQ", "gsk-test", null)));

		assertThat(response.getToolCalls()).singleElement().satisfies(call -> {
			assertThat(call.getId()).isEqualTo("call_7");
			assertThat(call.getName()).isEqualTo("lookup");
		});
	}

	@ParameterizedTest
	@MethodSource("com.itways.assistant.ai.service.impl.AgentTestSupport#providerFailures")
	void providerFailuresBecomeAnErrorReply(HttpStatus status) {
		server.expect(requestTo(URL)).andRespond(withStatus(status));

		AiResponse response = agent.chat(hello(config("GROQ", "gsk-test", null)));

		AiError error = assertFailure(response, kindOf(status));
		assertThat(error.getProviderStatus()).isEqualTo(status.value());
	}

	@Test
	void transcriptionIsAMultipartUploadWithTheWhisperDefault() {
		server.expect(requestTo("https://api.groq.com/openai/v1/audio/transcriptions"))
				.andExpect(header("Authorization", "Bearer gsk-test"))
				.andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
				.andExpect(request -> assertThat(body(request)).contains("whisper-large-v3")
						.contains("filename=\"clip.ogg\"").contains("\r\nen\r\n"))
				.andRespond(withSuccess("{\"text\":\"hello\"}", MediaType.APPLICATION_JSON));

		AiResponse response = agent.transcribe(AiTranscriptionRequest.builder()
				.config(config("GROQ", "gsk-test", null)).audioData(new byte[] { 1 }).filename("clip.ogg")
				.language("en-US").build());

		server.verify();
		assertThat(response.getContent()).isEqualTo("hello");
		assertThat(response.getModel()).isEqualTo("whisper-large-v3");
	}

	@Test
	void transcriptionFailureBecomesAnErrorReply() {
		server.expect(requestTo("https://api.groq.com/openai/v1/audio/transcriptions"))
				.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		AiResponse response = agent.transcribe(AiTranscriptionRequest.builder()
				.config(config("GROQ", "gsk-test", null)).audioData(new byte[] { 1 }).filename("clip.ogg").build());

		AiError error = assertFailure(response, AiError.Kind.RATE_LIMITED);
		assertThat(error.getProviderStatus()).isEqualTo(429);
	}

	private static AiChatRequest withImage(com.itways.assistant.ai.dto.AiRequestConfig config) {
		return AiChatRequest.builder().config(config)
				.messages(List.of(AiMessage.user("What is on this receipt?")))
				.files(List.of(
						AiWrappedFile.builder().filename("receipt.png").mimeType("image/png").content(PNG).build(),
						AiWrappedFile.builder().filename("notes.txt").mimeType("text/plain")
								.content("line one".getBytes(StandardCharsets.UTF_8)).build()))
				.build();
	}
}

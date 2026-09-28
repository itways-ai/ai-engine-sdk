package com.itways.assistant.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiError;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTool;
import com.itways.assistant.ai.dto.AiToolCall;

class AnthropicAgentTest extends AgentTestSupport {

	private static final String OK = """
			{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5",
			 "content":[{"type":"text","text":"Hi there"}],
			 "stop_reason":"end_turn","usage":{"input_tokens":12,"output_tokens":3}}
			""";

	private final AnthropicAgent agent = new AnthropicAgent(null, rest);

	/** What the agent logged at WARN or above during the test. */
	private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

	@BeforeEach
	void captureLog() {
		logged.start();
		((Logger) LoggerFactory.getLogger(AnthropicAgent.class)).addAppender(logged);
	}

	@AfterEach
	void releaseLog() {
		((Logger) LoggerFactory.getLogger(AnthropicAgent.class)).detachAppender(logged);
	}

	private List<String> warnings() {
		return logged.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
				.map(ILoggingEvent::getFormattedMessage).toList();
	}

	@Test
	void defaultRequestUsesOpusFiveWithCurrentHeadersAndNoSamplingParameters() {
		server.expect(requestTo("https://api.anthropic.com/v1/messages"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("x-api-key", "sk-test"))
				.andExpect(header("anthropic-version", "2023-06-01"))
				.andExpect(header("anthropic-beta", "server-side-fallback-2026-07-01"))
				.andExpect(jsonPath("$.model").value("claude-opus-5"))
				.andExpect(jsonPath("$.max_tokens").value(16000))
				.andExpect(jsonPath("$.temperature").doesNotExist())
				.andExpect(jsonPath("$.top_p").doesNotExist())
				.andExpect(jsonPath("$.top_k").doesNotExist())
				.andExpect(jsonPath("$.fallbacks").value("default"))
				.andExpect(jsonPath("$.messages[0].role").value("user"))
				.andExpect(jsonPath("$.messages[0].content").value("Hello"))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		AiChatRequest request = hello(config("CLAUDE", "sk-test", null));
		request.setTemperature(0.2);
		AiResponse response = agent.chat(request);

		server.verify();
		assertThat(response.getContent()).isEqualTo("Hi there");
		assertThat(response.getModel()).isEqualTo("claude-opus-5");
		assertThat(response.getUsage().getPromptTokens()).isEqualTo(12);
		assertThat(response.getUsage().getCompletionTokens()).isEqualTo(3);
		assertThat(response.getUsage().getTotalTokens()).isEqualTo(15);
	}

	@Test
	void accountModelOverridesTheDefaultAndOlderModelsStillGetTemperature() {
		server.expect(requestTo(AnthropicAgent.ANTHROPIC_URL))
				.andExpect(jsonPath("$.model").value("claude-sonnet-4-6"))
				.andExpect(jsonPath("$.temperature").value(0.3))
				.andExpect(jsonPath("$.max_tokens").value(512))
				.andExpect(jsonPath("$.fallbacks").doesNotExist())
				.andExpect(headerDoesNotExist("anthropic-beta"))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		AiChatRequest request = hello(config("CLAUDE", "sk-test", "claude-sonnet-4-6"));
		request.setTemperature(0.3);
		request.setMaxTokens(512);
		agent.chat(request);

		server.verify();
	}

	@Test
	void modelNamedOnTheRequestBeatsTheAccountModel() {
		server.expect(requestTo(AnthropicAgent.ANTHROPIC_URL))
				.andExpect(jsonPath("$.model").value("claude-haiku-4-5"))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		AiChatRequest request = hello(config("CLAUDE", "sk-test", "claude-sonnet-4-6"));
		request.setModel("claude-haiku-4-5");
		agent.chat(request);

		server.verify();
	}

	@Test
	void configuredDefaultModelAppliesWhenNobodyNamedOne() {
		AnthropicAgent configured = new AnthropicAgent(null, rest, "claude-sonnet-5", false);
		server.expect(requestTo(AnthropicAgent.ANTHROPIC_URL))
				.andExpect(jsonPath("$.model").value("claude-sonnet-5"))
				.andExpect(jsonPath("$.fallbacks").doesNotExist())
				.andExpect(headerDoesNotExist("anthropic-beta"))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		configured.chat(hello(config("CLAUDE", "sk-test", null)));

		server.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "claude-3-5-sonnet-20241022", "claude-3-7-sonnet-latest", "claude-3-haiku-20240307",
			"claude-haiku-4-5", "claude-sonnet-4-5", "claude-sonnet-4-5-20250929", "claude-opus-4-5",
			"claude-opus-4-6", "claude-sonnet-4-6", "claude-opus-4-1", "claude-sonnet-4-20250514" })
	void olderModelsAcceptSamplingParameters(String model) {
		assertThat(AnthropicAgent.acceptsSamplingParameters(model)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "claude-opus-5", "claude-opus-5-5", "claude-sonnet-5", "claude-opus-4-7",
			"claude-opus-4-8", "claude-fable-5", "claude-fable-5-1", "claude-sonnet-3.5", "some-future-model" })
	void currentAndUnknownModelsNeverGetSamplingParameters(String model) {
		assertThat(AnthropicAgent.acceptsSamplingParameters(model)).isFalse();
	}

	@Test
	void openingSystemMessagesGoToTheSystemFieldAndATrailingAssistantPrefillIsNotSent() {
		server.expect(requestTo(AnthropicAgent.ANTHROPIC_URL))
				.andExpect(jsonPath("$.system").value("Be brief.\n\nAnswer in English."))
				.andExpect(jsonPath("$.messages.length()").value(3))
				.andExpect(jsonPath("$.messages[0].role").value("user"))
				.andExpect(jsonPath("$.messages[0].content").value("Hi"))
				.andExpect(jsonPath("$.messages[1].role").value("assistant"))
				.andExpect(jsonPath("$.messages[2].role").value("user"))
				.andExpect(jsonPath("$.messages[2].content").value("Where do you deliver?"))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		AiChatRequest request = AiChatRequest.builder()
				.config(config("CLAUDE", "sk-test", null))
				.systemPrompt("Be brief.")
				.messages(List.of(AiMessage.system("Answer in English."), AiMessage.user("Hi"),
						AiMessage.assistant("Hello!"), AiMessage.user("Where do you deliver?"),
						AiMessage.assistant("We deliver to")))
				.build();
		agent.chat(request);

		server.verify();
	}

	@Test
	void aConversationOfOnlySystemMessagesIsStillSentAsAUserTurn() {
		server.expect(requestTo(AnthropicAgent.ANTHROPIC_URL))
				.andExpect(jsonPath("$.system").doesNotExist())
				.andExpect(jsonPath("$.messages[0].role").value("user"))
				.andExpect(jsonPath("$.messages[0].content").value("Summarise the rules."))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		agent.chat(AiChatRequest.builder().config(config("CLAUDE", "sk-test", null))
				.messages(List.of(AiMessage.system("Summarise the rules."))).build());

		server.verify();
	}

	@Test
	void toolsAndToolTrafficUseAnthropicContentBlocks() {
		server.expect(requestTo(AnthropicAgent.ANTHROPIC_URL))
				.andExpect(jsonPath("$.tools[0].name").value("lookup"))
				.andExpect(jsonPath("$.tools[0].input_schema.type").value("object"))
				.andExpect(jsonPath("$.messages[1].role").value("assistant"))
				.andExpect(jsonPath("$.messages[1].content[0].type").value("tool_use"))
				.andExpect(jsonPath("$.messages[1].content[0].id").value("toolu_1"))
				.andExpect(jsonPath("$.messages[1].content[0].input.q").value("Irbid"))
				.andExpect(jsonPath("$.messages[2].role").value("user"))
				.andExpect(jsonPath("$.messages[2].content[0].type").value("tool_result"))
				.andExpect(jsonPath("$.messages[2].content[0].tool_use_id").value("toolu_1"))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		AiToolCall call = AiToolCall.of("toolu_1", "lookup", Map.of("q", "Irbid"));
		agent.chat(AiChatRequest.builder().config(config("CLAUDE", "sk-test", null))
				.tools(List.of(AiTool.of("lookup", "Look something up",
						AiTool.object(Map.of("q", AiTool.string("query")), List.of("q")))))
				.messages(List.of(AiMessage.user("Do you deliver to Irbid?"),
						AiMessage.toolRequest(null, List.of(call)), AiMessage.toolResult(call, "yes")))
				.build());

		server.verify();
	}

	@Test
	void parsesTextAndToolUseAcrossThinkingAndFallbackBlocks() {
		server.expect(requestTo(AnthropicAgent.ANTHROPIC_URL)).andRespond(withSuccess("""
				{"model":"claude-opus-4-8","stop_reason":"tool_use",
				 "content":[
				   {"type":"fallback","from":{"model":"claude-opus-5"},"to":{"model":"claude-opus-4-8"}},
				   {"type":"thinking","thinking":"","signature":"sig"},
				   {"type":"text","text":"Let me check."},
				   {"type":"tool_use","id":"toolu_9","name":"lookup","input":{"q":"Irbid"}}],
				 "usage":{"input_tokens":5,"output_tokens":7}}
				""", MediaType.APPLICATION_JSON));

		AiResponse response = agent.chat(hello(config("CLAUDE", "sk-test", null)));

		assertThat(response.getContent()).isEqualTo("Let me check.");
		assertThat(response.getModel()).isEqualTo("claude-opus-4-8");
		assertThat(response.getToolCalls()).singleElement().satisfies(call -> {
			assertThat(call.getId()).isEqualTo("toolu_9");
			assertThat(call.getName()).isEqualTo("lookup");
			assertThat(call.argument("q")).isEqualTo("Irbid");
		});
	}

	@Test
	void aRefusalComesBackAsADistinguishableErrorNotAnEmptyAnswer() {
		server.expect(requestTo(AnthropicAgent.ANTHROPIC_URL)).andRespond(withSuccess("""
				{"model":"claude-opus-5","stop_reason":"refusal",
				 "stop_details":{"type":"refusal","category":"cyber"},
				 "content":[], "usage":{"input_tokens":5,"output_tokens":0}}
				""", MediaType.APPLICATION_JSON));

		AiResponse response = agent.chat(hello(config("CLAUDE", "sk-test", null)));

		AiError error = assertFailure(response, AiError.Kind.REFUSED);
		assertThat(error.getCategory()).isEqualTo("cyber");
		assertThat(error.getProviderStatus()).isNull();
		// The call was made and billed: model and usage are kept.
		assertThat(response.getModel()).isEqualTo("claude-opus-5");
		assertThat(response.getUsage().getPromptTokens()).isEqualTo(5);
	}

	@Test
	void aRefusalWithTextAfterItStillReadsAsARefusal() {
		server.expect(requestTo(AnthropicAgent.ANTHROPIC_URL)).andRespond(withSuccess("""
				{"model":"claude-opus-5","stop_reason":"refusal","stop_details":null,
				 "content":[{"type":"text","text":"I started to"}]}
				""", MediaType.APPLICATION_JSON));

		AiResponse response = agent.chat(hello(config("CLAUDE", "sk-test", null)));

		AiError error = assertFailure(response, AiError.Kind.REFUSED);
		assertThat(error.getCategory()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "claude-opus-5", "claude-opus-5-5", "claude-fable-5", "claude-fable-5-1" })
	void fallbacksAreNeverSentWithoutTheirBetaHeader(String model) {
		server.expect(requestTo(AnthropicAgent.ANTHROPIC_URL))
				.andExpect(jsonPath("$.fallbacks").value("default"))
				.andExpect(header("anthropic-beta", "server-side-fallback-2026-07-01"))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		agent.chat(hello(config("CLAUDE", "sk-test", model)));

		server.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "claude-opus-4-8", "claude-sonnet-5", "claude-haiku-4-5" })
	void modelsWithoutTheFallbackGetNeitherFieldNorHeader(String model) {
		server.expect(requestTo(AnthropicAgent.ANTHROPIC_URL))
				.andExpect(jsonPath("$.fallbacks").doesNotExist())
				.andExpect(headerDoesNotExist("anthropic-beta"))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		agent.chat(hello(config("CLAUDE", "sk-test", model)));

		server.verify();
	}

	@Test
	void defaultMaxTokensIsConfigurableAndAnExplicitValueWins() {
		AnthropicAgent configured = new AnthropicAgent(null, rest, null, true, 32000);
		server.expect(requestTo(AnthropicAgent.ANTHROPIC_URL))
				.andExpect(jsonPath("$.max_tokens").value(32000))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
		server.expect(requestTo(AnthropicAgent.ANTHROPIC_URL))
				.andExpect(jsonPath("$.max_tokens").value(1000))
				.andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

		configured.chat(hello(config("CLAUDE", "sk-test", null)));
		AiChatRequest explicit = hello(config("CLAUDE", "sk-test", null));
		explicit.setMaxTokens(1000);
		configured.chat(explicit);

		server.verify();
	}

	@Test
	void aReplyCutOffAtMaxTokensIsReturnedAndWarned() {
		server.expect(requestTo(AnthropicAgent.ANTHROPIC_URL)).andRespond(withSuccess("""
				{"model":"claude-opus-5","stop_reason":"max_tokens",
				 "content":[{"type":"thinking","thinking":"","signature":"s"},{"type":"text","text":"The answer is"}],
				 "usage":{"input_tokens":5,"output_tokens":16000}}
				""", MediaType.APPLICATION_JSON));

		AiResponse response = agent.chat(hello(config("CLAUDE", "sk-test", null)));

		assertThat(response.getContent()).isEqualTo("The answer is");
		assertThat(warnings()).anyMatch(w -> w.contains("cut off at max_tokens=16000"));
	}

	@ParameterizedTest
	@MethodSource("com.itways.assistant.ai.service.impl.AgentTestSupport#providerFailures")
	void providerFailuresBecomeAnErrorReplyInsteadOfAnException(HttpStatus status) {
		server.expect(requestTo(AnthropicAgent.ANTHROPIC_URL))
				.andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON)
						.body("{\"type\":\"error\",\"error\":{\"type\":\"x\",\"message\":\"nope\"}}"));

		AiResponse response = agent.chat(hello(config("CLAUDE", "sk-test", null)));

		AiError error = assertFailure(response, kindOf(status));
		assertThat(error.getProviderStatus()).isEqualTo(status.value());
		assertThat(error.getMessage()).isEqualTo("nope");
	}

	@Test
	void missingApiKeyIsReportedWithoutCallingTheProvider() {
		AiResponse response = agent.chat(hello(config("CLAUDE", null, null)));

		server.verify();
		AiError error = assertFailure(response, AiError.Kind.AUTH);
		assertThat(error.getMessage()).isEqualTo("Claude API key missing");
	}
}

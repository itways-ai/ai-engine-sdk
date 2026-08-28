package com.itways.assistant.ai.service.impl;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiRequestConfig;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.BaseAiRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Which model a call actually runs on.
 *
 * <p>This used to have one answer — the agent's own DEFAULT_MODEL — because the
 * model an account had configured was dropped on the way through the resolver and
 * no caller set it on the request. Nobody noticed until a provider retired one of
 * those defaults and every call for that account began failing with a 404 that no
 * amount of reconfiguring could fix. The precedence below is the fix, so it is
 * pinned here rather than left to each agent.
 */
@DisplayName("effective model resolution")
class EffectiveModelTest {

    /** Concrete stand-in: the helper lives on the abstract base, not on any one agent. */
    private static final class TestAgent extends AbstractAiAgent {
        TestAgent() {
            super(null, new RestTemplate());
        }

        @Override
        public String getProvider() {
            return "TEST";
        }

        @Override
        public AiResponse chat(AiChatRequest request) {
            return null;
        }

        @Override
        public AiResponse transcribe(com.itways.assistant.ai.dto.AiTranscriptionRequest request) {
            return null;
        }

        String resolve(String requested, BaseAiRequest request, String agentDefault) {
            return getEffectiveModel(requested, request, agentDefault);
        }
    }

    private final TestAgent agent = new TestAgent();

    private static AiChatRequest withConfiguredModel(String model) {
        return AiChatRequest.builder()
                .config(AiRequestConfig.builder().provider("gemini").apiKey("k").model(model).build())
                .build();
    }

    @Nested
    @DisplayName("precedence")
    class Precedence {

        @Test
        @DisplayName("a model named on the request wins over the account's")
        void requestWins() {
            assertThat(agent.resolve("on-request", withConfiguredModel("on-account"), "agent-default"))
                    .isEqualTo("on-request");
        }

        @Test
        @DisplayName("the account's configured model is used when the request names none")
        void accountIsUsed() {
            assertThat(agent.resolve(null, withConfiguredModel("on-account"), "agent-default"))
                    .isEqualTo("on-account");
        }

        @Test
        @DisplayName("the agent default applies only when neither names a model")
        void defaultIsLastResort() {
            assertThat(agent.resolve(null, withConfiguredModel(null), "agent-default"))
                    .isEqualTo("agent-default");
        }
    }

    @Nested
    @DisplayName("degenerate input")
    class Degenerate {

        @Test
        @DisplayName("a blank request model does not shadow the account's")
        void blankRequestModelIsIgnored() {
            assertThat(agent.resolve("   ", withConfiguredModel("on-account"), "agent-default"))
                    .isEqualTo("on-account");
        }

        @Test
        @DisplayName("a blank configured model falls through to the default")
        void blankConfiguredModelIsIgnored() {
            assertThat(agent.resolve(null, withConfiguredModel("  "), "agent-default"))
                    .isEqualTo("agent-default");
        }

        @Test
        @DisplayName("a request with no config at all still resolves")
        void noConfig() {
            assertThat(agent.resolve(null, AiChatRequest.builder().build(), "agent-default"))
                    .isEqualTo("agent-default");
            assertThat(agent.resolve(null, null, "agent-default")).isEqualTo("agent-default");
        }
    }

    @Nested
    @DisplayName("through a real agent")
    class ThroughAnAgent {

        @Test
        @DisplayName("GeminiAgent calls the model the account configured, not its own default")
        void geminiUsesConfiguredModel() {
            RestTemplate restTemplate = new RestTemplate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
            GeminiAgent gemini = new GeminiAgent(null, restTemplate);

            server.expect(requestTo(org.hamcrest.Matchers.containsString("models/gemini-3.5-flash:generateContent")))
                    .andRespond(withSuccess(
                            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}",
                            MediaType.APPLICATION_JSON));

            AiChatRequest request = AiChatRequest.builder()
                    .messages(List.of(AiMessage.user("hi")))
                    .config(AiRequestConfig.builder()
                            .provider("gemini")
                            .apiKey("k")
                            .model("gemini-3.5-flash")
                            .build())
                    .build();

            gemini.chat(request);

            server.verify();
        }
    }
}

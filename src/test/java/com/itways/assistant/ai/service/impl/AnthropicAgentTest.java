package com.itways.assistant.ai.service.impl;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * AnthropicAgent speaks the one provider dialect that does not use bearer
 * auth: the key rides in x-api-key next to a pinned anthropic-version. Like
 * GroqAgent, its failures are in-band strings inside AiResponse.content, so
 * the exact literals are pinned. The transcribe path is a permanent stub —
 * Claude has no audio transcription — and its error string is a contract too.
 */
@DisplayName("AnthropicAgent")
class AnthropicAgentTest {

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";

    private MockRestServiceServer server;
    private AnthropicAgent agent;

    @BeforeEach
    void bindServer() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        agent = new AnthropicAgent("anthropic-key", restTemplate);
    }

    private static AiChatRequest requestWith(AiMessage... messages) {
        return AiChatRequest.builder().messages(List.of(messages)).build();
    }

    private static String responseJson() {
        return """
                {
                  "model": "claude-sonnet-3.5",
                  "content": [{"type": "text", "text": "hello from claude"}],
                  "usage": {"input_tokens": 3, "output_tokens": 4}
                }
                """;
    }

    @Nested
    @DisplayName("chat")
    class Chat {

        @Test
        @DisplayName("the key travels as x-api-key beside the pinned anthropic-version header")
        void anthropicHeaders() {
            // NOTE: possible defect — the default model id "claude-sonnet-3.5"
            // is not a real Anthropic model id (real ids look like
            // "claude-3-5-sonnet-20241022"), so any request that relies on the
            // default model will be rejected by the live API.
            server.expect(requestTo(ANTHROPIC_URL))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("x-api-key", "anthropic-key"))
                    .andExpect(header("anthropic-version", "2023-06-01"))
                    .andExpect(jsonPath("$.model").value("claude-sonnet-3.5"))
                    .andExpect(jsonPath("$.max_tokens").value(4096))
                    .andRespond(withSuccess(responseJson(), MediaType.APPLICATION_JSON));

            agent.chat(requestWith(AiMessage.user("hi")));

            server.verify();
        }

        @Test
        @DisplayName("a system message is rewritten to a user turn before sending")
        void systemRoleRewritten() {
            // NOTE: possible defect — the Anthropic API has a dedicated
            // top-level "system" field; folding the system prompt into an
            // ordinary user message weakens it to just another user turn.
            server.expect(requestTo(ANTHROPIC_URL))
                    .andExpect(jsonPath("$.messages[0].role").value("user"))
                    .andExpect(jsonPath("$.messages[0].content").value("be brief"))
                    .andExpect(jsonPath("$.messages[1].role").value("user"))
                    .andRespond(withSuccess(responseJson(), MediaType.APPLICATION_JSON));

            agent.chat(requestWith(AiMessage.system("be brief"), AiMessage.user("hi")));

            server.verify();
        }

        @Test
        @DisplayName("text and usage are extracted, with the total computed from input plus output")
        void responseExtraction() {
            server.expect(requestTo(ANTHROPIC_URL))
                    .andRespond(withSuccess(responseJson(), MediaType.APPLICATION_JSON));

            AiResponse response = agent.chat(requestWith(AiMessage.user("hi")));

            assertThat(response.getContent()).isEqualTo("hello from claude");
            assertThat(response.getModel()).isEqualTo("claude-sonnet-3.5");
            assertThat(response.getUsage().getPromptTokens()).isEqualTo(3);
            assertThat(response.getUsage().getCompletionTokens()).isEqualTo(4);
            assertThat(response.getUsage().getTotalTokens()).isEqualTo(7);
        }

        @Test
        @DisplayName("a missing key and an HTTP failure each surface as their exact in-band literal")
        void errorLiterals() {
            // Consumers substring-match these strings; they are contracts.
            AnthropicAgent keylessAgent = new AnthropicAgent(null, new RestTemplate());
            assertThat(keylessAgent.chat(requestWith(AiMessage.user("hi"))).getContent())
                    .isEqualTo("Error: Claude API Key missing");

            server.expect(requestTo(ANTHROPIC_URL)).andRespond(withServerError());
            assertThat(agent.chat(requestWith(AiMessage.user("hi"))).getContent())
                    .startsWith("Claude API Error: ");
        }
    }

    @Nested
    @DisplayName("transcription")
    class Transcription {

        @Test
        @DisplayName("transcribe never calls out — it returns the unsupported-operation literal")
        void alwaysReturnsUnsupportedLiteral() {
            AiResponse response = agent.transcribe(AiTranscriptionRequest.builder().build());

            assertThat(response.getContent())
                    .isEqualTo("Error: Claude does not support audio transcription");
            // No expectations were registered, so this proves no HTTP happened.
            server.verify();
        }
    }
}

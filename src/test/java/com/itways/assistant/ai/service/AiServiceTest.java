package com.itways.assistant.ai.service;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiRequestConfig;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AiService is the single dispatch point between a request's declared provider
 * and the agent that serves it. The contracts pinned here are the routing
 * table: which agent a provider name reaches, that lookup is
 * case-insensitive on the caller's side, and that a missing or unknown
 * provider fails loudly instead of silently picking a default (the commented
 * out defaultAiAgent fallback in the source was deliberately abandoned).
 */
@DisplayName("AiService")
class AiServiceTest {

    /** Hand-written stub: records what reached it and answers with its own name. */
    private static final class StubAgent implements AiAgent {
        private final String provider;
        private AiChatRequest lastChat;
        private AiTranscriptionRequest lastTranscription;

        private StubAgent(String provider) {
            this.provider = provider;
        }

        @Override
        public AiResponse chat(AiChatRequest request) {
            this.lastChat = request;
            return AiResponse.builder().content("chat:" + provider).build();
        }

        @Override
        public AiResponse transcribe(AiTranscriptionRequest request) {
            this.lastTranscription = request;
            return AiResponse.builder().content("transcript:" + provider).build();
        }

        @Override
        public String getProvider() {
            return provider;
        }
    }

    private StubAgent groq;
    private StubAgent claude;
    private AiService service;

    @BeforeEach
    void buildService() {
        groq = new StubAgent("GROQ");
        claude = new StubAgent("CLAUDE");
        Map<String, AiAgent> agents = new HashMap<>();
        agents.put(groq.getProvider(), groq);
        agents.put(claude.getProvider(), claude);
        service = new AiService(agents);
    }

    private static AiChatRequest chatRequestFor(String provider) {
        return AiChatRequest.builder()
                .config(provider == null ? null
                        : AiRequestConfig.builder().provider(provider).build())
                .build();
    }

    private static AiTranscriptionRequest transcriptionRequestFor(String provider) {
        return AiTranscriptionRequest.builder()
                .config(provider == null ? null
                        : AiRequestConfig.builder().provider(provider).build())
                .build();
    }

    @Nested
    @DisplayName("chat dispatch")
    class ChatDispatch {

        @Test
        @DisplayName("the request's provider selects the matching agent and its answer comes back untouched")
        void routesToDeclaredProvider() {
            AiChatRequest request = chatRequestFor("GROQ");

            AiResponse response = service.chat(request);

            assertThat(response.getContent()).isEqualTo("chat:GROQ");
            assertThat(groq.lastChat).isSameAs(request);
            assertThat(claude.lastChat).isNull();
        }

        @Test
        @DisplayName("provider matching is case-insensitive — 'groq' and 'Claude' both route")
        void caseInsensitiveLookup() {
            assertThat(service.chat(chatRequestFor("groq")).getContent()).isEqualTo("chat:GROQ");
            assertThat(service.chat(chatRequestFor("Claude")).getContent()).isEqualTo("chat:CLAUDE");
        }

        @Test
        @DisplayName("a null provider is rejected, not defaulted")
        void nullProviderRejected() {
            AiChatRequest request = AiChatRequest.builder()
                    .config(AiRequestConfig.builder().provider(null).build())
                    .build();

            assertThatThrownBy(() -> service.chat(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No AI provider specified in the Request Config");
        }

        @Test
        @DisplayName("a request with no config at all fails the same way as a missing provider")
        void nullConfigRejected() {
            // The source reads the provider through a null-safe ternary, so a
            // missing config collapses into the missing-provider case rather
            // than a NullPointerException.
            assertThatThrownBy(() -> service.chat(chatRequestFor(null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No AI provider specified in the Request Config");
        }

        @Test
        @DisplayName("an unknown provider names itself in the rejection")
        void unsupportedProviderRejected() {
            assertThatThrownBy(() -> service.chat(chatRequestFor("BARD")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("provider BARD is not supported");
        }
    }

    @Nested
    @DisplayName("transcription dispatch")
    class TranscriptionDispatch {

        @Test
        @DisplayName("transcription routes by the same provider key, case-insensitively")
        void routesToDeclaredProvider() {
            AiTranscriptionRequest request = transcriptionRequestFor("groq");

            AiResponse response = service.transcribe(request);

            assertThat(response.getContent()).isEqualTo("transcript:GROQ");
            assertThat(groq.lastTranscription).isSameAs(request);
        }

        @Test
        @DisplayName("missing and unknown providers are rejected exactly as for chat")
        void rejectionMirrorsChat() {
            assertThatThrownBy(() -> service.transcribe(transcriptionRequestFor(null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No AI provider specified in the Request Config");
            assertThatThrownBy(() -> service.transcribe(transcriptionRequestFor("BARD")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("provider BARD is not supported");
        }
    }
}

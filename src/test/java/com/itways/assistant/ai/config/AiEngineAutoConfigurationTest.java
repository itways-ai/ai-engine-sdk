package com.itways.assistant.ai.config;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;
import com.itways.assistant.ai.service.AiAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The auto-configuration's aiAgents bean is the routing table AiService
 * dispatches through, so its keying rules are load-bearing: keys are the
 * providers' self-declared names, and a duplicate provider keeps the first
 * agent registered rather than exploding or silently swapping. The
 * aiRestTemplate bean is pinned only as far as "the custom HttpComponents
 * path built, not the fallback" — its trust-all TLS posture is documented
 * below rather than asserted.
 */
@DisplayName("AiEngineAutoConfiguration")
class AiEngineAutoConfigurationTest {

    private final AiEngineAutoConfiguration configuration = new AiEngineAutoConfiguration();

    /** Minimal agent whose only interesting property is its provider name. */
    private static final class NamedAgent implements AiAgent {
        private final String provider;

        private NamedAgent(String provider) {
            this.provider = provider;
        }

        @Override
        public AiResponse chat(AiChatRequest request) {
            return AiResponse.builder().content(provider).build();
        }

        @Override
        public AiResponse transcribe(AiTranscriptionRequest request) {
            return AiResponse.builder().content(provider).build();
        }

        @Override
        public String getProvider() {
            return provider;
        }
    }

    @Nested
    @DisplayName("aiAgents map building")
    class AiAgentsMap {

        @Test
        @DisplayName("each agent is keyed by exactly the name it declares")
        void keyedByDeclaredProvider() {
            NamedAgent groq = new NamedAgent("GROQ");
            NamedAgent claude = new NamedAgent("CLAUDE");

            Map<String, AiAgent> agents = configuration.aiAgents(List.of(groq, claude));

            assertThat(agents)
                    .containsOnlyKeys("GROQ", "CLAUDE")
                    .containsEntry("GROQ", groq)
                    .containsEntry("CLAUDE", claude);
        }

        @Test
        @DisplayName("keys are not normalized — a mixed-case provider name is stored as-is")
        void keysAreNotUpperCased() {
            // NOTE: possible defect — the map keys by AiAgent::getProvider
            // verbatim while AiService looks up provider.toUpperCase(). Every
            // shipped agent happens to return an upper-case constant, but an
            // agent declaring "Groq" would register fine and then be
            // unreachable through AiService ("provider Groq is not supported").
            Map<String, AiAgent> agents = configuration.aiAgents(List.of(new NamedAgent("Groq")));

            assertThat(agents).containsOnlyKeys("Groq");
        }

        @Test
        @DisplayName("two agents claiming one provider: the first one registered wins")
        void duplicateProviderFirstWins() {
            NamedAgent first = new NamedAgent("GROQ");
            NamedAgent second = new NamedAgent("GROQ");

            Map<String, AiAgent> agents = configuration.aiAgents(List.of(first, second));

            // The merge function is (existing, replacement) -> existing, so a
            // duplicate never throws and never displaces the incumbent. Which
            // agent is "first" is the List's order — under Spring that is bean
            // registration order, so this is quietly order-dependent.
            assertThat(agents).hasSize(1).containsEntry("GROQ", first);
        }
    }

    @Nested
    @DisplayName("aiRestTemplate")
    class AiRestTemplate {

        @Test
        @DisplayName("the relaxed-SSL HttpComponents client builds — the plain-RestTemplate fallback is not taken")
        void customClientBuilds() {
            // NOTE: possible defect — this RestTemplate trusts ALL TLS
            // certificates (loadTrustMaterial(null, (chain, authType) -> true))
            // and disables hostname verification (NoopHostnameVerifier), so a
            // man-in-the-middle between the service and any AI provider is
            // undetectable, and every request carries an API key. The comment
            // in the source says this is for corporate-firewall environments;
            // it deserves to be opt-in per deployment rather than
            // unconditional. Asserting the trust manager itself would mean
            // digging through Apache HttpClient internals, so this test only
            // pins that the custom path (not the catch-block fallback to
            // "new RestTemplate()") is what actually gets built.
            RestTemplate restTemplate = configuration.aiRestTemplate();

            assertThat(restTemplate.getRequestFactory())
                    .isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
        }
    }
}

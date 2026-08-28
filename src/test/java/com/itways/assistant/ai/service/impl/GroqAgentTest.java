package com.itways.assistant.ai.service.impl;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiRequestConfig;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;
import com.itways.assistant.ai.dto.AiWrappedFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * GroqAgent is the representative provider agent: hardcoded URLs, bearer
 * auth, a default model with a silent vision-model swap, and — crucially —
 * an error channel that is not exceptions but magic strings inside
 * AiResponse.content. Callers substring-match those strings, so their exact
 * literals are contracts and are pinned here verbatim.
 */
@DisplayName("GroqAgent")
class GroqAgentTest {

    private static final String CHAT_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String TRANSCRIPTION_URL = "https://api.groq.com/openai/v1/audio/transcriptions";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private GroqAgent agent;

    @BeforeEach
    void bindServer() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        agent = new GroqAgent("default-key", restTemplate);
    }

    private static AiChatRequest chatRequest(String model, List<AiWrappedFile> files) {
        return AiChatRequest.builder()
                .model(model)
                .messages(List.of(AiMessage.user("look at this")))
                .files(files)
                .build();
    }

    private static String chatResponseJson() {
        return """
                {
                  "model": "llama-3.3-70b-versatile",
                  "choices": [{"message": {"role": "assistant", "content": "the answer"}}],
                  "usage": {"prompt_tokens": 5, "completion_tokens": 7, "total_tokens": 12}
                }
                """;
    }

    @Nested
    @DisplayName("chat request mapping")
    class ChatRequestMapping {

        @Test
        @DisplayName("no model requested: the agent default, default temperature, and bearer auth go out")
        void defaultsAndAuth() {
            server.expect(requestTo(CHAT_URL))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer default-key"))
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    // The default moved when Groq retired the Llama line. The canned
                    // response below still echoes the old name on purpose: what comes
                    // back is the provider's to say, and parsing it is what is under test.
                    .andExpect(jsonPath("$.model").value("openai/gpt-oss-120b"))
                    .andExpect(jsonPath("$.temperature").value(0.1))
                    .andExpect(jsonPath("$.messages[0].role").value("user"))
                    .andExpect(jsonPath("$.messages[0].content").value("look at this"))
                    .andRespond(withSuccess(chatResponseJson(), MediaType.APPLICATION_JSON));

            agent.chat(chatRequest(null, null));

            server.verify();
        }

        @Test
        @DisplayName("an API key in the request config overrides the agent's default key")
        void requestKeyOverridesDefault() {
            server.expect(requestTo(CHAT_URL))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer per-request-key"))
                    .andRespond(withSuccess(chatResponseJson(), MediaType.APPLICATION_JSON));

            AiChatRequest request = chatRequest(null, null);
            request.setConfig(AiRequestConfig.builder().apiKey("per-request-key").build());
            agent.chat(request);

            server.verify();
        }

        @Test
        @DisplayName("an image attachment silently swaps the default model for the vision model")
        void imageSwapsToVisionModel() {
            AiWrappedFile image = AiWrappedFile.builder()
                    .filename("pic.png").mimeType("image/png")
                    .content("img".getBytes(StandardCharsets.UTF_8)).build();

            server.expect(requestTo(CHAT_URL))
                    .andExpect(jsonPath("$.model").value("meta-llama/llama-4-scout-17b-16e-instruct"))
                    // The last user message becomes multi-part: text plus a
                    // base64 data URL ("img" -> aW1n).
                    .andExpect(jsonPath("$.messages[0].content[0].type").value("text"))
                    .andExpect(jsonPath("$.messages[0].content[0].text").value("look at this"))
                    .andExpect(jsonPath("$.messages[0].content[1].image_url.url")
                            .value("data:image/png;base64,aW1n"))
                    .andRespond(withSuccess(chatResponseJson(), MediaType.APPLICATION_JSON));

            agent.chat(chatRequest(null, List.of(image)));

            server.verify();
        }

        @Test
        @DisplayName("an explicitly requested non-default model is never swapped, images or not")
        void explicitModelNotSwapped() {
            // The swap triggers only when the model is null, empty, or exactly
            // the default chat model — a deliberate "no specific model was
            // requested" test. An explicit choice is respected.
            AiWrappedFile image = AiWrappedFile.builder()
                    .filename("pic.png").mimeType("image/png")
                    .content("img".getBytes(StandardCharsets.UTF_8)).build();

            server.expect(requestTo(CHAT_URL))
                    .andExpect(jsonPath("$.model").value("llama-3.1-8b-instant"))
                    .andRespond(withSuccess(chatResponseJson(), MediaType.APPLICATION_JSON));

            agent.chat(chatRequest("llama-3.1-8b-instant", List.of(image)));

            server.verify();
        }
    }

    @Nested
    @DisplayName("chat response mapping")
    class ChatResponseMapping {

        @Test
        @DisplayName("content, model, and token usage come out of the provider payload")
        void happyPathParsed() {
            server.expect(requestTo(CHAT_URL))
                    .andRespond(withSuccess(chatResponseJson(), MediaType.APPLICATION_JSON));

            AiResponse response = agent.chat(chatRequest(null, null));

            assertThat(response.getContent()).isEqualTo("the answer");
            assertThat(response.getModel()).isEqualTo("llama-3.3-70b-versatile");
            assertThat(response.getUsage().getPromptTokens()).isEqualTo(5);
            assertThat(response.getUsage().getCompletionTokens()).isEqualTo(7);
            assertThat(response.getUsage().getTotalTokens()).isEqualTo(12);
        }

        @Test
        @DisplayName("a body with no choices maps to empty content rather than an error")
        void emptyChoicesTolerated() {
            server.expect(requestTo(CHAT_URL))
                    .andRespond(withSuccess("{\"choices\": []}", MediaType.APPLICATION_JSON));

            assertThat(agent.chat(chatRequest(null, null)).getContent()).isEmpty();
        }
    }

    @Nested
    @DisplayName("error contract")
    class ErrorContract {

        @Test
        @DisplayName("no API key anywhere: the literal 'Groq API Key missing' comes back as content")
        void missingKeyIsAnInBandString() {
            // NOTE: possible defect — errors are reported in-band as
            // AiResponse.content with no status flag, so consumers can only
            // detect them by substring-matching these exact literals. The
            // strings below are therefore API contracts; changing one breaks
            // every caller that greps for it.
            GroqAgent keylessAgent = new GroqAgent(null, restTemplate);

            AiResponse response = keylessAgent.chat(chatRequest(null, null));

            assertThat(response.getContent()).isEqualTo("Groq API Key missing");
            // And nothing was sent: the key check happens before any HTTP.
            server.verify();
        }

        @Test
        @DisplayName("an HTTP failure becomes a 'Groq API Error: ...' string, not an exception")
        void httpErrorIsAnInBandString() {
            server.expect(requestTo(CHAT_URL)).andRespond(withServerError());

            AiResponse response = agent.chat(chatRequest(null, null));

            assertThat(response.getContent())
                    .startsWith("Groq API Error: ")
                    .contains("500");
        }
    }

    @Nested
    @DisplayName("transcription")
    class Transcription {

        private AiTranscriptionRequest transcriptionRequest(String language) {
            return AiTranscriptionRequest.builder()
                    .audioData("audio-bytes".getBytes(StandardCharsets.UTF_8))
                    .filename("clip.wav")
                    .language(language)
                    .build();
        }

        @Test
        @DisplayName("audio goes to the transcription endpoint as multipart with the whisper default model")
        void multipartUpload() {
            server.expect(requestTo(TRANSCRIPTION_URL))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer default-key"))
                    .andExpect(request -> {
                        String body = ((MockClientHttpRequest) request).getBodyAsString();
                        assertThat(body)
                                .contains("filename=\"clip.wav\"")
                                .contains("whisper-large-v3")
                                // "en-US" is clipped to its ISO code before upload.
                                .contains("name=\"language\"")
                                .doesNotContain("en-US");
                    })
                    .andRespond(withSuccess("{\"text\": \"hello world\"}", MediaType.APPLICATION_JSON));

            AiResponse response = agent.transcribe(transcriptionRequest("en-US"));

            assertThat(response.getContent()).isEqualTo("hello world");
            assertThat(response.getModel()).isEqualTo("whisper-large-v3");
            server.verify();
        }

        @Test
        @DisplayName("transcription failures use a different in-band literal than chat failures")
        void errorStringDiffersFromChat() {
            // NOTE: possible defect — chat errors say "Groq API Error: ..."
            // but transcription errors say "Error calling Groq: ...". A
            // consumer matching one prefix silently misses the other.
            server.expect(requestTo(TRANSCRIPTION_URL)).andRespond(withServerError());

            AiResponse response = agent.transcribe(transcriptionRequest(null));

            assertThat(response.getContent()).startsWith("Error calling Groq: ");
        }

        @Test
        @DisplayName("a missing key blocks transcription with the same literal as chat")
        void missingKeySameLiteral() {
            GroqAgent keylessAgent = new GroqAgent(null, restTemplate);

            AiResponse response = keylessAgent.transcribe(transcriptionRequest(null));

            assertThat(response.getContent()).isEqualTo("Groq API Key missing");
            server.verify();
        }
    }
}

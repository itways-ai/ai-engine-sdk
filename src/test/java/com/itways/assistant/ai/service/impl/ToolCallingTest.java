package com.itways.assistant.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.web.client.RestTemplate;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiRequestConfig;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTool;
import com.itways.assistant.ai.dto.AiToolCall;

/**
 * Tool calling across the providers.
 *
 * <p>
 * Each vendor spells the same three things differently — declaring a tool,
 * reporting that the model called one, and handing the answer back — so each is
 * pinned against a canned reply in that vendor's own shape. What the tests
 * really protect is the round trip: a call that comes back must be replayable
 * to the same provider without loss, because that is the whole of a tool loop.
 */
@DisplayName("tool calling")
class ToolCallingTest {

    private static final AiTool LOOK_UP = AiTool.of("search_knowledge",
            "Look up a fact in the customer's knowledge base.",
            AiTool.object(Map.of("query", AiTool.string("what to look up")), List.of("query")));

    private RestTemplate restTemplate;
    private MockRestServiceServer server;

    @BeforeEach
    void bindServer() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    private static AiChatRequest asking(String provider) {
        return AiChatRequest.builder()
                .messages(List.of(AiMessage.user("what are your opening hours?")))
                .tools(List.of(LOOK_UP))
                .config(AiRequestConfig.builder().provider(provider).apiKey("k").build())
                .build();
    }

    /** The turn after a tool ran: question, the model's call, our answer to it. */
    private static AiChatRequest answering(String provider) {
        AiToolCall call = AiToolCall.of("call_1", "search_knowledge", Map.of("query", "opening hours"));
        return AiChatRequest.builder()
                .messages(List.of(
                        AiMessage.user("what are your opening hours?"),
                        AiMessage.toolRequest(null, List.of(call)),
                        AiMessage.toolResult(call, "Nine to five, Sunday to Thursday.")))
                .tools(List.of(LOOK_UP))
                .config(AiRequestConfig.builder().provider(provider).apiKey("k").build())
                .build();
    }

    // ───────────────────────────── OpenAI-compatible ─────────────────────────────

    @Nested
    @DisplayName("the providers that speak OpenAI's dialect")
    class OpenAiDialect {

        private static final String URL = "https://api.groq.com/openai/v1/chat/completions";

        @Test
        @DisplayName("tools are declared as functions, and a call comes back with its arguments parsed")
        void declaresAndReads() {
            server.expect(requestTo(URL))
                    .andExpect(jsonPath("$.tools[0].type").value("function"))
                    .andExpect(jsonPath("$.tools[0].function.name").value("search_knowledge"))
                    .andExpect(jsonPath("$.tools[0].function.parameters.required[0]").value("query"))
                    .andRespond(withSuccess("""
                            {"model":"m","choices":[{"message":{"role":"assistant","content":null,
                              "tool_calls":[{"id":"call_abc","type":"function",
                                "function":{"name":"search_knowledge","arguments":"{\\"query\\":\\"opening hours\\"}"}}]}}]}
                            """, MediaType.APPLICATION_JSON));

            AiResponse response = new GroqAgent("k", restTemplate).chat(asking("GROQ"));

            assertThat(response.hasToolCalls()).isTrue();
            AiToolCall call = response.getToolCalls().get(0);
            assertThat(call.getId()).isEqualTo("call_abc");
            assertThat(call.getName()).isEqualTo("search_knowledge");
            assertThat(call.argument("query")).isEqualTo("opening hours");
            server.verify();
        }

        @Test
        @DisplayName("the call and its answer are replayed as an assistant turn and a tool turn")
        void replaysTheRoundTrip() {
            server.expect(requestTo(URL))
                    .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                    .andExpect(jsonPath("$.messages[1].tool_calls[0].id").value("call_1"))
                    .andExpect(jsonPath("$.messages[1].tool_calls[0].function.name").value("search_knowledge"))
                    .andExpect(jsonPath("$.messages[1].tool_calls[0].function.arguments").value("{\"query\":\"opening hours\"}"))
                    .andExpect(jsonPath("$.messages[2].role").value("tool"))
                    .andExpect(jsonPath("$.messages[2].tool_call_id").value("call_1"))
                    .andExpect(jsonPath("$.messages[2].content").value("Nine to five, Sunday to Thursday."))
                    .andRespond(withSuccess("""
                            {"model":"m","choices":[{"message":{"role":"assistant","content":"We are open nine to five."}}]}
                            """, MediaType.APPLICATION_JSON));

            AiResponse response = new GroqAgent("k", restTemplate).chat(answering("GROQ"));

            assertThat(response.getContent()).isEqualTo("We are open nine to five.");
            assertThat(response.hasToolCalls()).isFalse();
            server.verify();
        }

        @Test
        @DisplayName("no tools declared means no tools field, so a plain chat is unchanged")
        void staysOutOfTheWay() {
            server.expect(requestTo(URL))
                    .andExpect(MockRestRequestMatchers.jsonPath("$.tools").doesNotExist())
                    .andRespond(withSuccess("""
                            {"model":"m","choices":[{"message":{"content":"Hello."}}]}
                            """, MediaType.APPLICATION_JSON));

            AiResponse response = new GroqAgent("k", restTemplate).chat(AiChatRequest.builder()
                    .messages(List.of(AiMessage.user("hello")))
                    .config(AiRequestConfig.builder().provider("GROQ").apiKey("k").build())
                    .build());

            assertThat(response.getContent()).isEqualTo("Hello.");
            assertThat(response.hasToolCalls()).isFalse();
            server.verify();
        }

        @Test
        @DisplayName("arguments the model mangled cost the arguments, not the call")
        void survivesMalformedArguments() {
            server.expect(requestTo(URL)).andRespond(withSuccess("""
                    {"model":"m","choices":[{"message":{"content":null,
                      "tool_calls":[{"id":"c1","function":{"name":"search_knowledge","arguments":"{not json"}}]}}]}
                    """, MediaType.APPLICATION_JSON));

            AiResponse response = new GroqAgent("k", restTemplate).chat(asking("GROQ"));

            assertThat(response.getToolCalls()).hasSize(1);
            assertThat(response.getToolCalls().get(0).getArguments()).isEmpty();
            server.verify();
        }
    }

    // ───────────────────────────── Gemini ─────────────────────────────

    @Nested
    @DisplayName("Gemini, which names tools rather than numbering them")
    class Gemini {

        @Test
        @DisplayName("a functionCall part is read as a call, and the answer goes back as a functionResponse")
        void callAndAnswer() {
            server.expect(MockRestRequestMatchers.jsonPath("$.tools[0].functionDeclarations[0].name")
                    .value("search_knowledge"))
                    .andRespond(withSuccess("""
                            {"candidates":[{"content":{"parts":[
                              {"functionCall":{"name":"search_knowledge","args":{"query":"opening hours"}}}]}}]}
                            """, MediaType.APPLICATION_JSON));

            AiResponse response = new GeminiAgent("k", restTemplate).chat(asking("GEMINI"));

            assertThat(response.hasToolCalls()).isTrue();
            assertThat(response.getToolCalls().get(0).argument("query")).isEqualTo("opening hours");
            server.verify();

            server = MockRestServiceServer.bindTo(restTemplate).build();
            server.expect(MockRestRequestMatchers.jsonPath("$.contents[1].parts[0].functionCall.name")
                    .value("search_knowledge"))
                    .andExpect(MockRestRequestMatchers.jsonPath("$.contents[2].parts[0].functionResponse.name")
                            .value("search_knowledge"))
                    .andRespond(withSuccess("""
                            {"candidates":[{"content":{"parts":[{"text":"We are open nine to five."}]}}]}
                            """, MediaType.APPLICATION_JSON));

            assertThat(new GeminiAgent("k", restTemplate).chat(answering("GEMINI")).getContent())
                    .isEqualTo("We are open nine to five.");
            server.verify();
        }

        @Test
        @DisplayName("text that follows a call in the same reply is still read")
        void textBesideACall() {
            server.expect(requestTo(org.hamcrest.Matchers.containsString("generativelanguage")))
                    .andRespond(withSuccess("""
                            {"candidates":[{"content":{"parts":[
                              {"text":"Let me check."},
                              {"functionCall":{"name":"search_knowledge","args":{"query":"hours"}}}]}}]}
                            """, MediaType.APPLICATION_JSON));

            AiResponse response = new GeminiAgent("k", restTemplate).chat(asking("GEMINI"));

            assertThat(response.getContent()).isEqualTo("Let me check.");
            assertThat(response.getToolCalls()).hasSize(1);
            server.verify();
        }
    }

    // ───────────────────────────── Anthropic ─────────────────────────────

    @Nested
    @DisplayName("Anthropic, which puts tool traffic inside the content blocks")
    class Anthropic {

        private static final String URL = "https://api.anthropic.com/v1/messages";

        @Test
        @DisplayName("a tool_use block is read as a call, and the answer goes back as a tool_result")
        void callAndAnswer() {
            server.expect(requestTo(URL))
                    .andExpect(jsonPath("$.tools[0].name").value("search_knowledge"))
                    .andExpect(jsonPath("$.tools[0].input_schema.type").value("object"))
                    .andRespond(withSuccess("""
                            {"model":"claude","content":[
                              {"type":"text","text":"Let me check."},
                              {"type":"tool_use","id":"toolu_1","name":"search_knowledge","input":{"query":"opening hours"}}]}
                            """, MediaType.APPLICATION_JSON));

            AiResponse response = new AnthropicAgent("k", restTemplate).chat(asking("ANTHROPIC"));

            assertThat(response.getContent()).isEqualTo("Let me check.");
            assertThat(response.getToolCalls()).hasSize(1);
            assertThat(response.getToolCalls().get(0).getId()).isEqualTo("toolu_1");
            server.verify();

            server = MockRestServiceServer.bindTo(restTemplate).build();
            server.expect(requestTo(URL))
                    .andExpect(jsonPath("$.messages[1].content[0].type").value("tool_use"))
                    .andExpect(jsonPath("$.messages[2].content[0].type").value("tool_result"))
                    .andExpect(jsonPath("$.messages[2].content[0].tool_use_id").value("call_1"))
                    .andRespond(withSuccess("""
                            {"model":"claude","content":[{"type":"text","text":"We are open nine to five."}]}
                            """, MediaType.APPLICATION_JSON));

            assertThat(new AnthropicAgent("k", restTemplate).chat(answering("ANTHROPIC")).getContent())
                    .isEqualTo("We are open nine to five.");
            server.verify();
        }
    }
}

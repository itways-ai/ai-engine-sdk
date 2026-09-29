package com.itways.assistant.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiError;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiRequestConfig;
import com.itways.assistant.ai.dto.AiResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Every agent talks to its provider through the RestTemplate it is given, so
 * the tests bind a {@link MockRestServiceServer} to that template: the agent's
 * real request is captured and checked, and nothing leaves the machine.
 */
abstract class AgentTestSupport {

    protected final RestTemplate rest = new RestTemplate();
    protected final MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();

    /** The provider failures every agent has to survive without throwing. */
    static List<HttpStatus> providerFailures() {
        return List.of(HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR,
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    /** What the agent wrote as the request body. */
    static String body(ClientHttpRequest request) {
        return ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8);
    }

    static AiRequestConfig config(String provider, String apiKey, String model) {
        return AiRequestConfig.builder().provider(provider).apiKey(apiKey).model(model).build();
    }

    /**
     * The kind a status in {@link #providerFailures()} must map to.
     */
    static AiError.Kind kindOf(HttpStatus status) {
        return switch (status.value()) {
        case 401 -> AiError.Kind.AUTH;
        case 429 -> AiError.Kind.RATE_LIMITED;
        default -> AiError.Kind.UNAVAILABLE;
        };
    }

    /** A failed call: the error set, no content and no tool calls in its place. */
    static AiError assertFailure(AiResponse response, AiError.Kind kind) {
        assertThat(response.isError()).as("isError").isTrue();
        assertThat(response.getContent()).as("content of a failed call").isNull();
        assertThat(response.hasToolCalls()).isFalse();
        assertThat(response.getError().getKind()).isEqualTo(kind);
        return response.getError();
    }

    static AiChatRequest hello(AiRequestConfig config) {
        return AiChatRequest.builder().config(config).messages(List.of(AiMessage.user("Hello"))).build();
    }
}

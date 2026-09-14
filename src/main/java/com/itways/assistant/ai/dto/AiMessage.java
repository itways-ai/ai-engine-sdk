package com.itways.assistant.ai.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiMessage {

    private String role; // system, user, assistant, tool

    private String content;

    /**
     * On an assistant message: the tools it asked for. Such a message usually
     * carries no content of its own, and must be replayed to the provider before
     * the results, or the results have nothing to answer.
     */
    private List<AiToolCall> toolCalls;

    /** On a tool message: which call this is the answer to. */
    private String toolCallId;

    /**
     * On a tool message: the tool that produced it. Redundant for the providers
     * that match a result to its call by id, and required by Gemini, which
     * matches by name instead.
     */
    private String toolName;

    public AiMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public static AiMessage system(String content) {
        return new AiMessage("system", content);
    }

    public static AiMessage user(String content) {
        return new AiMessage("user", content);
    }

    public static AiMessage assistant(String content) {
        return new AiMessage("assistant", content);
    }

    /** The assistant's turn when it asked for tools instead of answering. */
    public static AiMessage toolRequest(String content, List<AiToolCall> toolCalls) {
        AiMessage message = new AiMessage("assistant", content);
        message.setToolCalls(toolCalls);
        return message;
    }

    /** What a tool returned, addressed to the call that asked for it. */
    public static AiMessage toolResult(String toolCallId, String toolName, String content) {
        AiMessage message = new AiMessage("tool", content);
        message.setToolCallId(toolCallId);
        message.setToolName(toolName);
        return message;
    }

    /** The answer to a call, as the call itself described it. */
    public static AiMessage toolResult(AiToolCall call, String content) {
        return toolResult(call.getId(), call.getName(), content);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}

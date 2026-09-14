package com.itways.assistant.ai.dto;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The model asking for a tool to be run, and the handle to answer it with.
 *
 * <p>
 * The {@code id} matters more than it looks: a turn may carry several calls at
 * once, and each result has to be handed back against the call that asked for
 * it. Providers that do not issue ids of their own get one made up, so the
 * conversation shape is the same everywhere.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiToolCall {

	private String id;
	private String name;
	/** Arguments as the model supplied them, already parsed. Empty when it passed none. */
	private Map<String, Object> arguments;

	public static AiToolCall of(String id, String name, Map<String, Object> arguments) {
		return new AiToolCall(id, name, arguments == null ? Map.of() : arguments);
	}

	public String argument(String key) {
		Object value = arguments == null ? null : arguments.get(key);
		return value == null ? null : String.valueOf(value);
	}
}

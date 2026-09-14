package com.itways.assistant.ai.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Something the model may ask to have done, described well enough that it can
 * decide when to ask.
 *
 * <p>
 * The description is the whole contract: the model never sees the code behind
 * the tool, only these words, so "Look up a fact about the product in the
 * customer's own knowledge base" earns a call and "kb search" does not.
 *
 * <p>
 * {@code parameters} is a JSON Schema object, the shape every provider expects,
 * though each wraps it differently. {@link #object} builds the common case
 * without the ceremony.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTool {

	private String name;
	private String description;
	/** JSON Schema for the arguments; an object schema, even when it takes none. */
	private Map<String, Object> parameters;

	public static AiTool of(String name, String description, Map<String, Object> parameters) {
		return new AiTool(name, description, parameters);
	}

	/** A tool that takes no arguments. */
	public static AiTool of(String name, String description) {
		return new AiTool(name, description, object(Map.of(), List.of()));
	}

	/**
	 * A JSON Schema object.
	 *
	 * @param properties field name to its own schema, e.g.
	 *                   {@code "query", string("what to look up")}
	 * @param required   the field names the model must supply
	 */
	public static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", properties == null ? Map.of() : properties);
		schema.put("required", required == null ? List.of() : required);
		return schema;
	}

	public static Map<String, Object> string(String description) {
		Map<String, Object> field = new LinkedHashMap<>();
		field.put("type", "string");
		field.put("description", description);
		return field;
	}

	/** A string the model must pick from a fixed set — an intent, a status, a mode. */
	public static Map<String, Object> stringEnum(String description, List<String> values) {
		Map<String, Object> field = string(description);
		field.put("enum", values);
		return field;
	}
}

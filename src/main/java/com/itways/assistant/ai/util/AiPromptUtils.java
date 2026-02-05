package com.itways.assistant.ai.util;


import java.util.Collection;
import java.util.Map;

public final class AiPromptUtils {

	private AiPromptUtils() {
	}

	/*
	 * ========================================================= Section helpers
	 * =========================================================
	 */

	public static void appendSection(StringBuilder sb, String title, Object data) {
		if (data == null)
			return;

		sb.append(title).append(":\n");

		if (data instanceof Collection<?> collection) {
			appendCollection(sb, collection);
		} else if (data instanceof Map<?, ?> map) {
			appendMap(sb, map);
		} else {
			sb.append(data).append("\n");
		}
	}

	private static void appendCollection(StringBuilder sb, Collection<?> collection) {
		for (Object item : collection) {
			if (item instanceof Map<?, ?> map) {
				sb.append("- ");
				map.forEach((k, v) -> sb.append(k).append(": ").append(v).append(", "));
				trimComma(sb);
				sb.append("\n");
			} else {
				sb.append("- ").append(item).append("\n");
			}
		}
	}

	private static void appendMap(StringBuilder sb, Map<?, ?> map) {
		map.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
	}

	private static void trimComma(StringBuilder sb) {
		int len = sb.length();
		if (len >= 2 && sb.substring(len - 2).equals(", ")) {
			sb.delete(len - 2, len);
		}
	}

	/*
	 * ========================================================= Basic helpers
	 * (still reusable) =========================================================
	 */

	public static void appendQuoted(StringBuilder sb, String label, String value) {
		if (value == null || value.isBlank())
			return;
		sb.append(label).append(": \"").append(value).append("\"\n");
	}

	public static void appendFooter(StringBuilder sb, String text) {
		if (text == null || text.isBlank())
			return;
		sb.append("\n").append(text);
	}
	
	
	/**
	 * Cleans up AI response to extract only the JSON part.
	 */
	public static String cleanupJson(String json) {
		if (json == null || json.trim().isEmpty()) {
			return "{}";
		}
		String trimmed = json.trim();

		if (trimmed.contains("```json")) {
			int start = trimmed.indexOf("```json") + 7;
			int end = trimmed.indexOf("```", start);
			return (end != -1) ? trimmed.substring(start, end).trim() : trimmed.substring(start).trim();
		}

		if (trimmed.contains("```")) {
			int start = trimmed.indexOf("```") + 3;
			int end = trimmed.indexOf("```", start);
			return (end != -1) ? trimmed.substring(start, end).trim() : trimmed.substring(start).trim();
		}

		int start = trimmed.indexOf('{');
		int end = trimmed.lastIndexOf('}');
		if (start != -1 && end != -1 && end > start) {
			return trimmed.substring(start, end + 1).trim();
		}

		int bracketStart = trimmed.indexOf('[');
		int bracketEnd = trimmed.lastIndexOf(']');
		if (bracketStart != -1 && bracketEnd != -1 && bracketEnd > bracketStart) {
			return trimmed.substring(bracketStart, bracketEnd + 1).trim();
		}

		return trimmed;
	}
}

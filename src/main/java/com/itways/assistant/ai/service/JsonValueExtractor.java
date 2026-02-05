package com.itways.assistant.ai.service;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JsonValueExtractor {

	private final ObjectMapper objectMapper;

	/**
	 * Extracts a value from JSON by key (supports nested paths).
	 *
	 * Examples: - "intent" - "entities.to" - "items[0].name"
	 */
	public String getValue(String text, String path) {
		try {
			JsonNode root = extractJsonNode(text);
			JsonNode node = resolvePath(root, path);
			return node != null && !node.isMissingNode() && !node.isNull() ? node.asText() : null;
		} catch (Exception e) {
			return null;
		}
	}

	/*
	 * ========================================================= Internals
	 * =========================================================
	 */

	private JsonNode extractJsonNode(String text) throws Exception {
		return objectMapper.readTree(text);
	}

	private JsonNode resolvePath(JsonNode node, String path) {
		for (String part : path.split("\\.")) {
			if (part.contains("[")) {
				String name = part.substring(0, part.indexOf('['));
				int index = Integer.parseInt(part.substring(part.indexOf('[') + 1, part.indexOf(']')));
				node = node.path(name).path(index);
			} else {
				node = node.path(part);
			}
		}
		return node;
	}
}

package com.itways.assistant.ai.service.impl;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.ai.dto.AiError;
import com.itways.assistant.ai.dto.AiError.Kind;
import com.itways.assistant.ai.dto.AiResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Turns a provider failure into an {@link AiError}, the same way for every agent.
 *
 * <p>
 * The message kept on the error is the provider's own one-line explanation
 * when its body has one, otherwise the HTTP reason, with anything that looks
 * like an API key removed and the length capped. It is for logs and operators;
 * the reply a user sees is the caller's to choose.
 */
@Slf4j
final class ProviderErrors {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final int MAX_MESSAGE = 300;

	/**
	 * Key shapes worth scrubbing even when they are not the key this call used:
	 * OpenAI and Anthropic ({@code sk-…}, {@code sk-ant-…}), Groq ({@code gsk_…}),
	 * Google ({@code AIza…}). OpenAI's 401 quotes a masked prefix and suffix of
	 * the key it was given.
	 */
	private static final Pattern KEY_SHAPES = Pattern.compile("\\b(sk-|gsk_|AIza)[A-Za-z0-9_\\-*.]{4,}");

	/**
	 * Gemini block reasons ({@code promptFeedback.blockReason}) and finish reasons
	 * that mean the answer was withheld rather than finished.
	 */
	private static final Set<String> GEMINI_BLOCKED = Set.of("SAFETY", "RECITATION", "BLOCKLIST",
			"PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "OTHER");

	private ProviderErrors() {
	}

	/** An exception thrown while calling {@code provider}, as a failed response. */
	static AiResponse fromException(String provider, Exception e, String apiKey) {
		AiError error = classify(provider, e, apiKey);
		if (error.getKind() == Kind.OTHER) {
			// Not an HTTP status or a network failure: a response this agent could
			// not read. That is a bug worth a stack trace.
			log.error("{} call failed: {}", provider, error.getMessage(), e);
		} else {
			log.warn("{} call failed: {}: {}", provider, error.summary(), error.getMessage());
		}
		return AiResponse.failure(error);
	}

	static AiError classify(String provider, Exception e, String apiKey) {
		if (e instanceof RestClientResponseException http) {
			int status = http.getStatusCode().value();
			String body = safeBody(http);
			String message = providerMessage(body);
			if (message == null || message.isBlank()) {
				message = http.getStatusText() == null || http.getStatusText().isBlank() ? "HTTP " + status
						: http.getStatusText();
			}
			return AiError.builder().kind(kindFor(status, body)).provider(provider).providerStatus(status)
					.message(redact(message, apiKey)).build();
		}
		if (e instanceof ResourceAccessException) {
			boolean timedOut = isTimeout(e);
			return AiError.builder().kind(timedOut ? Kind.TIMEOUT : Kind.UNAVAILABLE).provider(provider)
					.message(timedOut ? "The provider did not answer in time"
							: "The provider could not be reached (" + rootCauseName(e) + ")")
					.build();
		}
		return AiError.builder().kind(Kind.OTHER).provider(provider)
				.message("Unexpected " + e.getClass().getSimpleName() + " while reading the provider's response")
				.build();
	}

	static Kind kindFor(int status, String body) {
		if (status == 429) {
			return Kind.RATE_LIMITED;
		}
		if (status == 401 || status == 403) {
			return Kind.AUTH;
		}
		if (status == 408 || status == 504) {
			return Kind.TIMEOUT;
		}
		if (status >= 500) {
			// Includes Anthropic's 529 "overloaded".
			return Kind.UNAVAILABLE;
		}
		if (status == 400 && body != null && body.contains("API_KEY_INVALID")) {
			// Gemini answers a bad key with 400 INVALID_ARGUMENT, reason API_KEY_INVALID.
			return Kind.AUTH;
		}
		if (status >= 400) {
			return Kind.BAD_REQUEST;
		}
		return Kind.OTHER;
	}

	static AiResponse missingKey(String provider, String providerLabel) {
		log.warn("{} API key missing", providerLabel);
		return AiResponse.failure(AiError.builder().kind(Kind.AUTH).provider(provider)
				.message(providerLabel + " API key missing").build());
	}

	static AiResponse unsupported(String provider, String what) {
		log.warn("{} does not support {}", provider, what);
		return AiResponse.failure(AiError.builder().kind(Kind.BAD_REQUEST).provider(provider)
				.message(provider + " does not support " + what).build());
	}

	static AiResponse empty(String provider) {
		log.warn("{} returned an empty or unreadable response", provider);
		return AiResponse.failure(AiError.builder().kind(Kind.OTHER).provider(provider)
				.message("The provider returned no answer").build());
	}

	/** The model declined. {@code model} and {@code usage} are kept: the call was made and billed. */
	static AiResponse refused(String provider, String category, String model, AiResponse.Usage usage) {
		log.warn("{} declined the request (model {}, category {})", provider, model, category);
		return AiResponse.builder()
				.error(AiError.builder().kind(Kind.REFUSED).provider(provider).category(category)
						.message("The model declined the request" + (category == null ? "" : " (" + category + ")"))
						.build())
				.model(model)
				.usage(usage)
				.build();
	}

	/**
	 * The refusal category of an OpenAI-compatible choice, or null when it answered.
	 * {@code message.refusal} is set when a model declines under structured output;
	 * {@code finish_reason: content_filter} with nothing left to show is the
	 * provider's filter withholding the answer. A filtered choice that still has
	 * text or tool calls is returned as the answer it is.
	 */
	static String openAiRefusal(Map<?, ?> choice, Map<?, ?> message) {
		if (message != null && message.get("refusal") instanceof String refusal && !refusal.isBlank()) {
			return "refusal";
		}
		if (choice != null && "content_filter".equals(choice.get("finish_reason")) && !hasAnswer(message)) {
			return "content_filter";
		}
		return null;
	}

	private static boolean hasAnswer(Map<?, ?> message) {
		if (message == null) {
			return false;
		}
		boolean text = message.get("content") instanceof String content && !content.isBlank();
		boolean tools = message.get("tool_calls") instanceof java.util.List<?> calls && !calls.isEmpty();
		return text || tools;
	}

	/** Whether a Gemini block or finish reason means the answer was withheld. */
	static boolean geminiBlocked(Object reason) {
		return reason instanceof String r && GEMINI_BLOCKED.contains(r.toUpperCase(Locale.ROOT));
	}

	/** Anything that looks like a key, and the key itself, replaced; then capped. */
	static String redact(String message, String apiKey) {
		if (message == null) {
			return null;
		}
		String clean = message;
		if (apiKey != null && apiKey.length() >= 4) {
			clean = clean.replace(apiKey, "[redacted]");
		}
		clean = KEY_SHAPES.matcher(clean).replaceAll("[redacted]");
		clean = clean.replaceAll("\\s+", " ").trim();
		return clean.length() > MAX_MESSAGE ? clean.substring(0, MAX_MESSAGE) + "…" : clean;
	}

	/**
	 * The provider's explanation from its error body. OpenAI, Groq, Mistral and
	 * Anthropic use {@code {"error": {"message": …}}}, Gemini adds a status to the
	 * same, and some Mistral errors are a flat {@code {"message": …}}.
	 */
	private static String providerMessage(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			JsonNode root = JSON.readTree(body);
			if (root == null) {
				return null;
			}
			JsonNode error = root.get("error");
			if (error != null && error.isObject() && error.hasNonNull("message")) {
				return error.get("message").asText();
			}
			if (error != null && error.isTextual()) {
				return error.asText();
			}
			if (root.hasNonNull("message")) {
				return root.get("message").asText();
			}
			if (root.hasNonNull("detail")) {
				return root.get("detail").isTextual() ? root.get("detail").asText() : root.get("detail").toString();
			}
		} catch (Exception notJson) {
			// An HTML error page from a proxy, typically. The status says enough.
		}
		return null;
	}

	private static String safeBody(RestClientResponseException e) {
		try {
			return e.getResponseBodyAsString();
		} catch (Exception unreadable) {
			return null;
		}
	}

	private static boolean isTimeout(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException
					|| t instanceof TimeoutException
					|| (t instanceof InterruptedIOException && t.getClass().getSimpleName().contains("Timeout"))) {
				return true;
			}
		}
		return false;
	}

	private static String rootCauseName(Throwable e) {
		Throwable root = e;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		return root.getClass().getSimpleName();
	}
}

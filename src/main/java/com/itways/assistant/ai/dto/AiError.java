package com.itways.assistant.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Why a call produced no answer.
 *
 * <p>
 * Set on {@link AiResponse#getError()} instead of an answer, never alongside
 * one: when it is present, {@code content} is null. Every field is safe to log
 * and to show an operator. None of them carries the API key or the prompt, and
 * none of them should be shown to an end user as a reply.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiError {

	public enum Kind {
		/** 429: a per-minute or quota limit. Worth one retry after a pause. */
		RATE_LIMITED,
		/** 401/403, a key the provider rejected, or no key configured at all. */
		AUTH,
		/** The model declined to answer. The provider's reason is in {@link AiError#getCategory()}. */
		REFUSED,
		/** A read or connect timeout, or a 408/504 from the provider. */
		TIMEOUT,
		/** 5xx (including Anthropic's 529 overloaded), or the provider could not be reached. */
		UNAVAILABLE,
		/** The provider rejected the request itself (400, 404, 413, 422 …), or the agent cannot do what was asked. */
		BAD_REQUEST,
		/** Anything else: an unexpected or empty response. */
		OTHER;

		/** Whether asking again, after a short pause, can reasonably produce an answer. */
		public boolean isRetryable() {
			return this == RATE_LIMITED || this == TIMEOUT || this == UNAVAILABLE;
		}
	}

	private Kind kind;
	/** The agent's provider name ({@code CLAUDE}, {@code OPENAI}, …). */
	private String provider;
	/**
	 * The HTTP status the provider answered with, when it answered with an error
	 * status. Null for a refusal (the provider answered 200), a timeout, a network
	 * failure or a missing key.
	 */
	private Integer providerStatus;
	/**
	 * For {@link Kind#REFUSED}, the provider's own reason when it gives one: the
	 * Claude {@code stop_details.category}, OpenAI's {@code content_filter}, a
	 * Gemini block or finish reason. Null otherwise.
	 */
	private String category;
	/** A short, redacted description for logs and operators. Never a reply. */
	private String message;

	@JsonIgnore
	public boolean isRetryable() {
		return kind != null && kind.isRetryable();
	}

	/** {@code RATE_LIMITED (GROQ 429)}: the kind and status, without the message, for one log line. */
	public String summary() {
		StringBuilder summary = new StringBuilder(String.valueOf(kind));
		StringBuilder detail = new StringBuilder();
		if (provider != null) {
			detail.append(provider);
		}
		if (providerStatus != null) {
			detail.append(detail.length() > 0 ? " " : "").append(providerStatus);
		}
		if (category != null) {
			detail.append(detail.length() > 0 ? ", " : "").append(category);
		}
		if (detail.length() > 0) {
			summary.append(" (").append(detail).append(')');
		}
		return summary.toString();
	}
}

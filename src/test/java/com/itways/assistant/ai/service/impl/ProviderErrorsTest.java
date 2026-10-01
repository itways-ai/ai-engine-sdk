package com.itways.assistant.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.itways.assistant.ai.dto.AiError.Kind;
import com.itways.assistant.ai.dto.AiResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/** The structured {@code error.details[]} of a provider error, logged in full and without keys. */
class ProviderErrorsTest {

    private static final String KEY = "AIzaSyD-test_0123456789abcdefghijklmnop";

    /** A Gemini free-tier 429 as the API returns it, with a key echoed in a help link for good measure. */
    private static final String GEMINI_429 = """
            {
              "error": {
                "code": 429,
                "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/usage?tab=rate-limit. \\n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 50, model: gemini-2.5-pro\\nPlease retry in 41.528s.",
                "status": "RESOURCE_EXHAUSTED",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.QuotaFailure",
                    "violations": [
                      {
                        "quotaMetric": "generativelanguage.googleapis.com/generate_content_free_tier_requests",
                        "quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier",
                        "quotaDimensions": {"location": "global", "model": "gemini-2.5-pro"},
                        "quotaValue": "50"
                      }
                    ]
                  },
                  {
                    "@type": "type.googleapis.com/google.rpc.Help",
                    "links": [
                      {"description": "Learn more about Gemini API quotas",
                       "url": "https://ai.google.dev/gemini-api/docs/rate-limits?key=AIzaSyD-test_0123456789abcdefghijklmnop&hl=en"}
                    ]
                  },
                  {
                    "@type": "type.googleapis.com/google.rpc.RetryInfo",
                    "retryDelay": "41s"
                  }
                ]
              }
            }
            """;

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logged.start();
        ((Logger) LoggerFactory.getLogger(ProviderErrors.class)).addAppender(logged);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(ProviderErrors.class)).detachAppender(logged);
    }

    @Test
    void geminiQuotaFailureIsLoggedInFullOnOneExtraWarnLine() {
        AiResponse response = ProviderErrors.fromException("GEMINI", tooManyRequests(GEMINI_429), KEY);

        assertThat(response.getError().getKind()).isEqualTo(Kind.RATE_LIMITED);
        // The free-text message keeps its cap.
        assertThat(response.getError().getMessage()).hasSizeLessThanOrEqualTo(301).endsWith("…");

        List<String> warnings = logged.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(1)).isEqualTo("GEMINI error details: "
                + "quotaMetric=generativelanguage.googleapis.com/generate_content_free_tier_requests, "
                + "quotaId=GenerateRequestsPerDayPerProjectPerModel-FreeTier, model=gemini-2.5-pro, "
                + "location=global, quotaValue=50; "
                + "help=https://ai.google.dev/gemini-api/docs/rate-limits?key=[redacted]&hl=en; "
                + "retryDelay=41s");
        assertThat(String.join("\n", warnings)).doesNotContain(KEY).doesNotContain("AIza");
    }

    @Test
    void keysAreScrubbedFromDetailsEvenWhenNotTheCallersKey() {
        String body = """
                {"error":{"details":[
                  {"@type":"type.googleapis.com/google.rpc.QuotaFailure",
                   "violations":[{"quotaMetric":"m AIzaOtherKey_9","quotaId":"q","quotaValue":"1"}]},
                  {"@type":"type.googleapis.com/google.rpc.Help","links":[{"url":"https://x.test/a?KEY=secret"}]}]}}
                """;

        assertThat(ProviderErrors.errorDetails(body, null))
                .isEqualTo("quotaMetric=m [redacted], quotaId=q, quotaValue=1; help=https://x.test/a?KEY=[redacted]");
    }

    @Test
    void anErrorWithoutDetailsLogsNoExtraLine() {
        String body = "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit_error\"}}";

        ProviderErrors.fromException("OPENAI", tooManyRequests(body), "sk-live-0123456789abcdef");

        assertThat(logged.list).hasSize(1);
        assertThat(ProviderErrors.errorDetails(body, null)).isNull();
        assertThat(ProviderErrors.errorDetails("<html>busy</html>", null)).isNull();
        assertThat(ProviderErrors.errorDetails(null, null)).isNull();
    }

    private static HttpClientErrorException tooManyRequests(String body) {
        return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}

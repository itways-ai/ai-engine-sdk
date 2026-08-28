package com.itways.assistant.ai.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cleanupJson is the tolerance layer between "the model was asked for JSON"
 * and "the model wrapped its JSON in markdown fences and a paragraph of
 * chatter". Everything downstream feeds its output straight into a JSON
 * parser, so what this returns for each input shape is a contract. The
 * section helpers are pinned more lightly: they format the prompt text the
 * models actually see.
 */
@DisplayName("AiPromptUtils")
class AiPromptUtilsTest {

    @Nested
    @DisplayName("cleanupJson")
    class CleanupJson {

        @Test
        @DisplayName("a ```json fence is stripped down to the payload inside it")
        void jsonFenceStripped() {
            String raw = "Here is your answer:\n```json\n{\"a\": 1}\n```\nHope that helps!";

            assertThat(AiPromptUtils.cleanupJson(raw)).isEqualTo("{\"a\": 1}");
        }

        @Test
        @DisplayName("a bare ``` fence works the same way")
        void plainFenceStripped() {
            assertThat(AiPromptUtils.cleanupJson("```\n{\"a\": 1}\n```"))
                    .isEqualTo("{\"a\": 1}");
        }

        @Test
        @DisplayName("an opening fence that is never closed still yields everything after it")
        void unterminatedFenceTolerated() {
            assertThat(AiPromptUtils.cleanupJson("```json\n{\"a\": 1}"))
                    .isEqualTo("{\"a\": 1}");
        }

        @Test
        @DisplayName("plain JSON passes through untouched apart from trimming")
        void plainJsonPassthrough() {
            assertThat(AiPromptUtils.cleanupJson("  {\"a\": 1}  ")).isEqualTo("{\"a\": 1}");
        }

        @Test
        @DisplayName("prose before and after an unfenced object is cut away")
        void proseAroundObjectRemoved() {
            String raw = "Sure! The result is {\"a\": 1, \"b\": {\"c\": 2}} — let me know.";

            // The heuristic is first '{' to last '}', so nested braces survive.
            assertThat(AiPromptUtils.cleanupJson(raw))
                    .isEqualTo("{\"a\": 1, \"b\": {\"c\": 2}}");
        }

        @Test
        @DisplayName("a top-level array is extracted too, but only when no object braces appear first")
        void arrayExtracted() {
            assertThat(AiPromptUtils.cleanupJson("The list: [1, 2, 3]. Done."))
                    .isEqualTo("[1, 2, 3]");
            // NOTE: possible defect — the '{'..'}' scan runs before the
            // '['..']' scan, so an array of objects like [{"a":1},{"b":2}]
            // is clipped to the span between the first '{' and the last '}',
            // dropping the surrounding array brackets.
            assertThat(AiPromptUtils.cleanupJson("[{\"a\": 1}, {\"b\": 2}]"))
                    .isEqualTo("{\"a\": 1}, {\"b\": 2}");
        }

        @Test
        @DisplayName("null and blank collapse to an empty object literal")
        void nullAndBlank() {
            assertThat(AiPromptUtils.cleanupJson(null)).isEqualTo("{}");
            assertThat(AiPromptUtils.cleanupJson("   ")).isEqualTo("{}");
        }

        @Test
        @DisplayName("text with no JSON in it at all is returned trimmed, not replaced with {}")
        void noJsonReturnsTrimmedInput() {
            // Callers that parse the result must therefore still be prepared
            // for non-JSON; the helper does not guarantee parseable output.
            assertThat(AiPromptUtils.cleanupJson("  I cannot answer that.  "))
                    .isEqualTo("I cannot answer that.");
        }
    }

    @Nested
    @DisplayName("prompt section building")
    class SectionBuilding {

        @Test
        @DisplayName("null data appends nothing — not even the section title")
        void nullDataSkipsSection() {
            StringBuilder sb = new StringBuilder();

            AiPromptUtils.appendSection(sb, "Context", null);

            assertThat(sb).isEmpty();
        }

        @Test
        @DisplayName("a collection renders as a dash list, a map of items as comma-joined pairs")
        void collectionAndMapShapes() {
            StringBuilder sb = new StringBuilder();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", 7);
            row.put("name", "x");

            AiPromptUtils.appendSection(sb, "Items", List.of("one", row));

            assertThat(sb.toString()).isEqualTo("Items:\n- one\n- id: 7, name: x\n");
        }

        @Test
        @DisplayName("appendQuoted skips null and blank values and quotes the rest")
        void quotedValues() {
            StringBuilder sb = new StringBuilder();

            AiPromptUtils.appendQuoted(sb, "Goal", null);
            AiPromptUtils.appendQuoted(sb, "Goal", "  ");
            AiPromptUtils.appendQuoted(sb, "Goal", "ship it");

            assertThat(sb.toString()).isEqualTo("Goal: \"ship it\"\n");
        }
    }
}

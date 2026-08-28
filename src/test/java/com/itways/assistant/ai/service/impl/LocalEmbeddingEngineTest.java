package com.itways.assistant.ai.service.impl;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LocalEmbeddingEngine wraps an Ollama model that only exists at runtime, so
 * these tests never touch the network: the langchain4j builder is verified to
 * be side-effect-free at construction (it just captures configuration), and
 * the private embeddingModel field is then swapped for a mock so the
 * mini-batch slicing in embedBatch — the only real logic in the class — can
 * be observed directly.
 */
@DisplayName("LocalEmbeddingEngine")
class LocalEmbeddingEngineTest {

    /**
     * Points at a port nothing listens on; if construction ever tried to
     * connect, this would fail and the constructor-safety test would catch it.
     */
    private static LocalEmbeddingEngine engineWithDeadUrl() {
        return new LocalEmbeddingEngine("http://127.0.0.1:1");
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("building against an unreachable URL succeeds — the builder does not connect")
        void constructionIsOffline() {
            // This is what makes the class safe to instantiate in contexts
            // (and tests) where Ollama is absent: the first network contact
            // happens on the first embed call, not in the constructor.
            assertThat(engineWithDeadUrl()).isNotNull();
        }
    }

    @Nested
    @DisplayName("input guards")
    class InputGuards {

        @Test
        @DisplayName("embed rejects null and blank text before any model call")
        void embedRejectsNullAndBlank() {
            LocalEmbeddingEngine engine = engineWithDeadUrl();

            assertThatThrownBy(() -> engine.embed(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Input text cannot be null or blank");
            assertThatThrownBy(() -> engine.embed("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Input text cannot be null or blank");
        }

        @Test
        @DisplayName("embedBatch answers null or empty input with an empty list, silently")
        void embedBatchToleratesNothingToDo() {
            LocalEmbeddingEngine engine = engineWithDeadUrl();

            assertThat(engine.embedBatch(null)).isEmpty();
            assertThat(engine.embedBatch(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("mini-batch slicing")
    class MiniBatchSlicing {

        @Test
        @DisplayName("70 texts travel as windows of 32, 32, and 6, and come back in order")
        void slicesIntoWindowsOf32() {
            LocalEmbeddingEngine engine = engineWithDeadUrl();
            List<Integer> batchSizes = new ArrayList<>();
            int[] counter = {0};

            EmbeddingModel recordingModel = mock(EmbeddingModel.class);
            when(recordingModel.embedAll(anyList())).thenAnswer(invocation -> {
                List<TextSegment> segments = invocation.getArgument(0);
                batchSizes.add(segments.size());
                // Tag each vector with its global position so reordering or
                // dropping a window would show up in the output.
                List<Embedding> embeddings = segments.stream()
                        .map(segment -> Embedding.from(new float[]{counter[0]++}))
                        .toList();
                return Response.from(embeddings);
            });
            ReflectionTestUtils.setField(engine, "embeddingModel", recordingModel);

            List<String> texts = IntStream.range(0, 70).mapToObj(i -> "text-" + i).toList();
            List<float[]> vectors = engine.embedBatch(texts);

            assertThat(batchSizes).containsExactly(32, 32, 6);
            assertThat(vectors).hasSize(70);
            assertThat(vectors.get(0)[0]).isEqualTo(0f);
            assertThat(vectors.get(31)[0]).isEqualTo(31f);
            assertThat(vectors.get(32)[0]).isEqualTo(32f);
            assertThat(vectors.get(69)[0]).isEqualTo(69f);
        }

        @Test
        @DisplayName("a batch smaller than one window goes out as a single call")
        void smallBatchSingleWindow() {
            LocalEmbeddingEngine engine = engineWithDeadUrl();
            List<Integer> batchSizes = new ArrayList<>();

            EmbeddingModel recordingModel = mock(EmbeddingModel.class);
            when(recordingModel.embedAll(anyList())).thenAnswer(invocation -> {
                List<TextSegment> segments = invocation.getArgument(0);
                batchSizes.add(segments.size());
                return Response.from(segments.stream()
                        .map(segment -> Embedding.from(new float[]{1f})).toList());
            });
            ReflectionTestUtils.setField(engine, "embeddingModel", recordingModel);

            assertThat(engine.embedBatch(List.of("a", "b", "c"))).hasSize(3);
            assertThat(batchSizes).containsExactly(3);
        }
    }
}

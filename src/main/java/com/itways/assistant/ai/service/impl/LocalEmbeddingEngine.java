package com.itways.assistant.ai.service.impl;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component // Pure Spring utility component, not Ai agent
public class LocalEmbeddingEngine {

    private static final String OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String DEFAULT_MODEL = "granite-embedding:278m";
    private static final int INTERNAL_MINI_BATCH_SIZE = 32;

    private final Map<String, EmbeddingModelSpec> supportedModels = new LinkedHashMap<>();
    private final Map<String, EmbeddingModel> modelCache = new ConcurrentHashMap<>();

    public LocalEmbeddingEngine() {
        supportedModels.put("granite-embedding:278m", new EmbeddingModelSpec("granite-embedding:278m", 768));
        supportedModels.put("bge-m3", new EmbeddingModelSpec("bge-m3", 1024));
        supportedModels.put("nomic-embed-text", new EmbeddingModelSpec("nomic-embed-text", 768));

        log.info("Local Ollama embedding engine ready. defaultModel={}, supportedModels={}",
                DEFAULT_MODEL, supportedModels.keySet());
    }

    /**
     * Generates a vector for a single query using the default embedding model.
     */
    public float[] embed(String text) {
        return embed(text, DEFAULT_MODEL);
    }

    /**
     * Generates a vector for a single query using the selected embedding model.
     */
    public float[] embed(String text, String modelName) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Input text cannot be null or blank");
        }
        return modelFor(modelName).embed(text).content().vector();
    }

    /**
     * Bulk processing pipeline using safe sequential mini-batches with the default model.
     */
    public List<float[]> embedBatch(List<String> texts) {
        return embedBatch(texts, DEFAULT_MODEL);
    }

    /**
     * Bulk processing pipeline using safe sequential mini-batches with the selected model.
     */
    public List<float[]> embedBatch(List<String> texts, String modelName) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        EmbeddingModelSpec spec = getModelSpec(modelName);
        EmbeddingModel embeddingModel = modelFor(spec.name());

        long startTime = System.currentTimeMillis();
        List<float[]> allVectors = new ArrayList<>(texts.size());

        log.info("Processing batch of {} items with model='{}' dimensions={} using mini-batches of {}",
                texts.size(), spec.name(), spec.dimension(), INTERNAL_MINI_BATCH_SIZE);

        for (int i = 0; i < texts.size(); i += INTERNAL_MINI_BATCH_SIZE) {
            List<String> subList = texts.subList(i, Math.min(i + INTERNAL_MINI_BATCH_SIZE, texts.size()));

            List<TextSegment> segments = subList.stream()
                    .map(TextSegment::from)
                    .collect(Collectors.toList());

            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

            for (Embedding embedding : embeddings) {
                allVectors.add(embedding.vector());
            }
        }

        log.info("Local batch inference completed in {}ms total for {} items with model='{}'",
                (System.currentTimeMillis() - startTime), texts.size(), spec.name());
        return allVectors;
    }

    public String getDefaultModelName() {
        return DEFAULT_MODEL;
    }

    public int getDimension(String modelName) {
        return getModelSpec(modelName).dimension();
    }

    public boolean isSupportedModel(String modelName) {
        return modelName != null && supportedModels.containsKey(modelName.trim());
    }

    public List<EmbeddingModelSpec> getSupportedModels() {
        return List.copyOf(supportedModels.values());
    }

    private EmbeddingModel modelFor(String modelName) {
        EmbeddingModelSpec spec = getModelSpec(modelName);
        return modelCache.computeIfAbsent(spec.name(), this::buildOllamaModel);
    }

    private EmbeddingModel buildOllamaModel(String modelName) {
        log.info("Connecting to local Ollama embedding model '{}'", modelName);
        return OllamaEmbeddingModel.builder()
                .baseUrl(OLLAMA_BASE_URL)
                .modelName(modelName)
                .build();
    }

    private EmbeddingModelSpec getModelSpec(String modelName) {
        String resolvedModelName = (modelName == null || modelName.isBlank())
                ? DEFAULT_MODEL
                : modelName.trim();

        EmbeddingModelSpec spec = supportedModels.get(resolvedModelName);
        if (spec == null) {
            throw new IllegalArgumentException("Unsupported embedding model: " + resolvedModelName);
        }
        return spec;
    }

    public record EmbeddingModelSpec(String name, int dimension) {
    }
}

package com.itways.assistant.ai.service.impl;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component // Pure Spring utility component, not Ai agent
public class LocalEmbeddingEngine {

    private final EmbeddingModel embeddingModel;
//    private static final String MODEL = "bge-m3";
    private static final String MODEL = "granite-embedding:278m";
    private static final int INTERNAL_MINI_BATCH_SIZE = 32;

    public LocalEmbeddingEngine() {
        log.info("🧠 Connecting to local Ollama instance inside Docker space...");
        this.embeddingModel = OllamaEmbeddingModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName(MODEL)
                .build();

        log.info("✅ Ollama Multilingual embedding model bound successfully (768 Dimensions).");
    }

    /**
     * Generates a 768-dimensional float vector for a single query.
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Input text cannot be null or blank");
        }
        return embeddingModel.embed(text).content().vector();
    }

//    public List<float[]> embedBatch(List<String> texts) {
//        if (texts == null || texts.isEmpty()) return List.of();
//
//        long startTime = System.currentTimeMillis();
//
//        // 1. Convert raw strings into LangChain4j TextSegments
//        List<TextSegment> segments = texts.stream()
//                .map(TextSegment::from)
//                .collect(Collectors.toList());
//
//        log.info("🚀 Passing batch of {} segments directly to local ONNX matrix engine...", segments.size());
//
//        // 2. Execute vector transformations simultaneously using parallel CPU threads
//        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
//
//        // Extract float vector layouts backout
//        List<float[]> vectors = embeddings.stream()
//                .map(Embedding::vector)
//                .collect(Collectors.toList());
//
//        log.info("⚡ Native batch inference completed in {}ms total for {} items",
//                (System.currentTimeMillis() - startTime), texts.size());
//
//        return vectors;
//    }
    /**
     * Bulk processing pipeline using safe sequential mini-batches
     * to pipe raw data arrays smoothly to the Docker network container.
     */
    public List<float[]> embedBatch(List<String> texts){
        if(texts == null || texts.isEmpty()){
            return List.of();
        }

        long startTime = System.currentTimeMillis();
        List<float[]> allVectors = new ArrayList<>(texts.size());

        log.info("🚀 Processing batch of {} items using internal mini-batches of {}...",
                texts.size(), INTERNAL_MINI_BATCH_SIZE);

        // Slice into optimized internal windows (e.g., 32 at a time)
        for(int i = 0; i < texts.size(); i += INTERNAL_MINI_BATCH_SIZE){
            List<String> subList = texts.subList(i,Math.min(i + INTERNAL_MINI_BATCH_SIZE, texts.size()));

            // 1. Map sublist to textSegments
            List<TextSegment> segments = subList.stream()
                    .map(TextSegment::from)
                    .collect(Collectors.toList());

            // Run bulk native model processing inside the container environment
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

            // 3. collect vectors
            for(Embedding embedding : embeddings){
                allVectors.add(embedding.vector());
            }
        }
        log.info("⚡ Optimized local batch inference completed in {}ms total for {} items",
                (System.currentTimeMillis() - startTime), texts.size());
        return allVectors;
    }
}
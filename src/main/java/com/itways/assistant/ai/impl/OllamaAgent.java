package com.itways.assistant.ai.impl;

import com.itways.assistant.ai.config.AiAgent;
import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiTranscriptionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Slf4j
public class OllamaAgent implements AiAgent {

    private final String baseUrl;
    
    // CodeLlama is best for structured JSON output (95%+ valid JSON)
    // Alternatives: "llama3:instruct" (85% valid) or "mistral:7b-instruct" (90% valid)
    // DO NOT use llama3.2:1b or llama3.2:3b for JSON - they generate invalid syntax
    // llama3.2:1b , codellama:7b-instruct , phi3:mini
    private static final String DEFAULT_MODEL = "llama3.2:1b";
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";
    private static final int CONNECT_TIMEOUT = 10000;  // 10 seconds
    private static final int READ_TIMEOUT = 180000;    // 3 minutes for slow CPU inference

    @Override
    public String getProvider() {
        return "OLLAMA";
    }

    @Override
    public AiResponse chat(AiChatRequest request) {
        String effectiveBaseUrl = (baseUrl != null && !baseUrl.isEmpty()) ? baseUrl : DEFAULT_BASE_URL;
        String ollamaUrl = effectiveBaseUrl + "/api/chat";
        String modelToUse = request.getModel() != null ? request.getModel() : DEFAULT_MODEL;
        
        log.info("Ollama chat request to: {} with model: {}", ollamaUrl, modelToUse);

        // Create RestTemplate with custom timeouts for Ollama
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        RestTemplate restTemplate = new RestTemplate(requestFactory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("model", modelToUse);
        body.put("messages", request.getMessages().stream()
                .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                .collect(Collectors.toList()));
        body.put("stream", false);
        
        // Keep model loaded in memory for faster subsequent requests (30 minutes)
        body.put("keep_alive", "30m");
        
        // Performance-optimized options
        Map<String, Object> options = new HashMap<>();
        
        // Temperature
        if (request.getTemperature() != null) {
            options.put("temperature", request.getTemperature());
        }
        
        // Limit tokens for faster response (default 512 if not specified)
        options.put("num_predict", request.getMaxTokens() != null ? request.getMaxTokens() : 512);
        
        // Reduce context window for better performance (2048 instead of default 4096)
        options.put("num_ctx", 2048);
        
        // Use more CPU threads for parallel processing
        options.put("num_thread", 8);
        
        // Optimize sampling for speed
        options.put("top_k", 40);
        options.put("top_p", 0.9);
        
        body.put("options", options);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        
        long startTime = System.currentTimeMillis();
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(ollamaUrl, entity, Map.class);
            long duration = System.currentTimeMillis() - startTime;
            
            Map<String, Object> responseBody = response.getBody();
            if (responseBody != null) {
                Map<String, Object> message = (Map<String, Object>) responseBody.get("message");
                if (message != null) {
                    String content = (String) message.get("content");
                    
                    // Extract usage information if available
                    AiResponse.Usage usage = null;
                    Integer promptTokens = (Integer) responseBody.get("prompt_eval_count");
                    Integer completionTokens = (Integer) responseBody.get("eval_count");
                    
                    if (promptTokens != null && completionTokens != null) {
                        usage = new AiResponse.Usage(
                                promptTokens,
                                completionTokens,
                                promptTokens + completionTokens);
                        
                        // Log performance metrics
                        Long evalDuration = responseBody.get("eval_duration") != null 
                            ? ((Number) responseBody.get("eval_duration")).longValue() 
                            : 0L;
                        
                        if (evalDuration > 0) {
                            double tokensPerSecond = (completionTokens * 1_000_000_000.0) / evalDuration;
                            log.info("Ollama response: {}ms total, {:.2f} tokens/sec, {} tokens", 
                                    duration, tokensPerSecond, completionTokens);
                        } else {
                            log.info("Ollama response received in {}ms", duration);
                        }
                    } else {
                        log.info("Ollama response received in {}ms", duration);
                    }

                    return AiResponse.builder()
                            .content(content)
                            .model((String) responseBody.get("model"))
                            .usage(usage)
                            .build();
                }
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Ollama API Error after {}ms: {}", duration, e.getMessage());
            
            String errorMsg = "Ollama API Error: " + e.getMessage() + 
                    ". Make sure Ollama is running at " + effectiveBaseUrl;
            
            // Add performance hint if it's taking too long
            if (duration > 60000) {
                errorMsg += ". Response is slow - consider using a smaller model like 'llama3.2:1b' " +
                           "or enabling GPU. See OLLAMA_PERFORMANCE_GUIDE.md for optimization tips.";
            }
            
            return AiResponse.builder().content(errorMsg).build();
        }
        return AiResponse.builder().content("").build();
    }

    @Override
    public AiResponse transcribe(AiTranscriptionRequest request) {
        return AiResponse.builder()
                .content("Error: Ollama does not support audio transcription natively. " +
                        "Consider using OpenAI's Whisper model for transcription.")
                .build();
    }
}

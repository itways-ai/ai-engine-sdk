# Ollama Performance Optimization Guide

## Why is Ollama Slow? (5 minutes vs 2-3 seconds)

### Root Causes:

1. **CPU-Only Inference** 🐌
   - Ollama in Docker likely running on CPU only
   - Gemini runs on Google's TPUs (1000x faster)
   - CPU inference: ~5-10 tokens/second
   - GPU inference: ~50-200 tokens/second

2. **Model Loading Time**
   - First request loads model into memory (30-60 seconds)
   - Subsequent requests should be faster
   - Large models (4.7GB) take longer to load

3. **Docker Resource Limits**
   - Limited CPU/RAM allocation
   - No GPU passthrough configured
   - Shared resources with other containers

4. **Model Size vs Hardware**
   - 7B parameter models need significant compute
   - 3B models are faster but still slow on CPU

## Quick Wins (Immediate Improvements)

### 1. Keep Model Loaded (Warm Cache)

Ollama unloads models after 5 minutes of inactivity. Keep it warm:

```bash
# Keep model loaded in memory
docker exec -it ollama ollama run llama3.2:3b "hello" 

# Or set keep_alive parameter
curl http://localhost:11434/api/generate -d '{
  "model": "llama3.2:3b",
  "keep_alive": -1
}'
```

Update OllamaAgent to keep models loaded:

```java
// Add to request body in OllamaAgent.chat()
body.put("keep_alive", "30m");  // Keep loaded for 30 minutes
```

### 2. Use Smaller, Faster Models

| Model | Size | Speed (CPU) | Quality |
|-------|------|-------------|---------|
| phi3:mini | 2.2 GB | ⚡⚡⚡ Fast (~10s) | Good |
| llama3.2:3b | 2.0 GB | ⚡⚡⚡ Fast (~10s) | Good |
| llama3.2:1b | 1.3 GB | ⚡⚡⚡⚡ Very Fast (~5s) | Decent |
| deepseek-r1:7b | 4.7 GB | 🐌 Slow (5+ min) | Excellent |

**Recommendation**: Use `llama3.2:1b` or `phi3:mini` for speed.

```bash
# Pull the fastest model
docker exec -it ollama ollama pull llama3.2:1b
```

### 3. Reduce Token Generation

Limit output length to speed up responses:

```java
Map<String, Object> options = new HashMap<>();
options.put("num_predict", 256);  // Limit to 256 tokens (was unlimited)
options.put("temperature", 0.7);
body.put("options", options);
```

### 4. Increase Docker Resources

Give Docker more CPU/RAM:

**Docker Desktop Settings:**
- CPUs: 6-8 cores (more is better)
- Memory: 8-16 GB
- Swap: 2 GB

**Command Line:**
```bash
docker update ollama --cpus="6" --memory="8g"
```

## Advanced Optimizations

### 1. Enable GPU Acceleration (MASSIVE Speed Boost)

**For NVIDIA GPU:**

```bash
# Stop current container
docker stop ollama
docker rm ollama

# Run with GPU support
docker run -d --gpus=all -v ollama:/root/.ollama -p 11434:11434 --name ollama ollama/ollama

# Verify GPU is detected
docker exec -it ollama nvidia-smi
```

**Expected improvement**: 5 minutes → 10-30 seconds

### 2. Use Quantized Models

Quantized models are smaller and faster with minimal quality loss:

```bash
# Q4 quantization (4-bit) - 2x faster, 50% smaller
docker exec -it ollama ollama pull llama3.2:3b-q4_0

# Q8 quantization (8-bit) - balanced
docker exec -it ollama ollama pull llama3.2:3b-q8_0
```

### 3. Optimize Model Parameters

Update OllamaAgent for better performance:

```java
Map<String, Object> options = new HashMap<>();
options.put("num_predict", 512);        // Limit output tokens
options.put("temperature", 0.7);        
options.put("top_k", 40);               // Reduce sampling space
options.put("top_p", 0.9);              
options.put("num_ctx", 2048);           // Reduce context window (default 4096)
options.put("num_thread", 8);           // Use more CPU threads
body.put("options", options);
```

### 4. Use Streaming for Better UX

Even if total time is the same, streaming feels faster:

```java
body.put("stream", true);  // Enable streaming

// Handle streaming response
// (requires WebFlux or SSE implementation)
```

## Recommended Configuration Updates

### Update OllamaAgent.java for Performance:

```java
@Override
public AiResponse chat(AiChatRequest request) {
    String effectiveBaseUrl = (baseUrl != null && !baseUrl.isEmpty()) ? baseUrl : DEFAULT_BASE_URL;
    String ollamaUrl = effectiveBaseUrl + "/api/chat";
    
    log.info("Ollama chat request to: {} with model: {}", ollamaUrl, 
            request.getModel() != null ? request.getModel() : DEFAULT_MODEL);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    
    // Set longer timeout for Ollama
    RestTemplate restTemplate = new RestTemplate();
    restTemplate.setRequestFactory(new SimpleClientHttpRequestFactory() {{
        setConnectTimeout(10000);      // 10 seconds
        setReadTimeout(120000);        // 2 minutes (was infinite)
    }});

    Map<String, Object> body = new HashMap<>();
    body.put("model", request.getModel() != null ? request.getModel() : DEFAULT_MODEL);
    body.put("messages", request.getMessages().stream()
            .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
            .collect(Collectors.toList()));
    body.put("stream", false);
    body.put("keep_alive", "30m");  // Keep model loaded
    
    // Performance-optimized options
    Map<String, Object> options = new HashMap<>();
    options.put("num_predict", request.getMaxTokens() != null ? request.getMaxTokens() : 512);
    options.put("temperature", request.getTemperature() != null ? request.getTemperature() : 0.7);
    options.put("num_ctx", 2048);      // Smaller context window
    options.put("num_thread", 8);      // More CPU threads
    options.put("top_k", 40);
    options.put("top_p", 0.9);
    body.put("options", options);

    // Rest of the code...
}
```

## Performance Comparison Table

| Configuration | Speed | Quality | Cost |
|--------------|-------|---------|------|
| **Gemini (Cloud)** | ⚡⚡⚡⚡⚡ 2-3s | ⭐⭐⭐⭐⭐ | $$ |
| **Ollama + GPU + 1B model** | ⚡⚡⚡⚡ 5-10s | ⭐⭐⭐ | Free |
| **Ollama + GPU + 3B model** | ⚡⚡⚡ 10-30s | ⭐⭐⭐⭐ | Free |
| **Ollama + CPU + 3B model** | ⚡ 30-60s | ⭐⭐⭐⭐ | Free |
| **Ollama + CPU + 7B model** | 🐌 5+ min | ⭐⭐⭐⭐⭐ | Free |

## Recommended Setup for Best Performance

### Option 1: GPU-Accelerated (Best Performance)
```bash
# Requires NVIDIA GPU
docker run -d --gpus=all \
  -v ollama:/root/.ollama \
  -p 11434:11434 \
  --name ollama \
  ollama/ollama

# Use 3B model with quantization
docker exec -it ollama ollama pull llama3.2:3b-q4_0
```

**Expected**: 10-30 seconds per request

### Option 2: CPU-Optimized (No GPU)
```bash
# Give Docker max resources
docker update ollama --cpus="8" --memory="8g"

# Use smallest fast model
docker exec -it ollama ollama pull llama3.2:1b

# Keep model loaded
docker exec -it ollama ollama run llama3.2:1b "warmup"
```

**Expected**: 15-45 seconds per request

### Option 3: Hybrid Approach (Recommended)
```properties
# Use Ollama for simple/fast queries
ai.active-provider=OLLAMA
ai.ollama.base-url=http://localhost:11434

# Fallback to Gemini for complex queries
ai.gemini.api-key=${GEMINI_API_KEY}
```

Implement smart routing:
```java
public AiResponse chat(String message, boolean isComplex) {
    if (isComplex || message.length() > 500) {
        // Use fast cloud provider
        return geminiService.chat(message);
    } else {
        // Use local Ollama
        return ollamaService.chat(message);
    }
}
```

## Diagnostic Commands

### Check Current Performance:

```bash
# Test model speed
time docker exec -it ollama ollama run llama3.2:3b "Say hello"

# Check if GPU is being used
docker exec -it ollama nvidia-smi

# Monitor resource usage
docker stats ollama

# Check model load status
curl http://localhost:11434/api/ps
```

### Benchmark Different Models:

```bash
# Test 1B model
time curl http://localhost:11434/api/generate -d '{
  "model": "llama3.2:1b",
  "prompt": "Explain AI in one sentence",
  "stream": false
}'

# Test 3B model
time curl http://localhost:11434/api/generate -d '{
  "model": "llama3.2:3b",
  "prompt": "Explain AI in one sentence",
  "stream": false
}'
```

## Expected Results After Optimization

| Before | After (CPU) | After (GPU) |
|--------|-------------|-------------|
| 5 minutes | 20-40 seconds | 10-20 seconds |

## When to Use What

**Use Ollama (Local) when:**
- ✅ Privacy is critical
- ✅ Simple queries (summaries, classifications)
- ✅ Development/testing
- ✅ Cost is a concern

**Use Gemini/Cloud when:**
- ✅ Speed is critical (< 5 seconds)
- ✅ Complex reasoning needed
- ✅ Long context windows
- ✅ Production workloads

## Next Steps

1. **Immediate**: Switch to `llama3.2:1b` model
2. **Short-term**: Increase Docker resources
3. **Long-term**: Add GPU support or use hybrid approach
4. **Best**: Implement smart routing based on query complexity

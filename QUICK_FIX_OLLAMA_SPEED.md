# 🚀 Quick Fix: Ollama Speed (5 min → 30 sec)

## Immediate Actions (Do These Now!)

### 1. Switch to Fastest Model (30 seconds)
```bash
# Pull the 1B model (smallest, fastest)
docker exec -it ollama ollama pull llama3.2:1b

# Keep it loaded in memory
docker exec -it ollama ollama run llama3.2:1b "hello"
```

Update your code to use it:
```java
AiChatRequest request = AiChatRequest.builder()
    .model("llama3.2:1b")  // Changed from 3b or 7b
    .message("your prompt")
    .maxTokens(256)        // Limit output length
    .build();
```

**Expected improvement**: 5 min → 30-60 sec

### 2. Increase Docker Resources (2 minutes)

**Windows Docker Desktop:**
1. Right-click Docker icon → Settings
2. Resources → Advanced
3. Set CPUs: 6-8 cores
4. Set Memory: 8 GB
5. Click "Apply & Restart"

**Or via command:**
```bash
docker update ollama --cpus="6" --memory="8g"
docker restart ollama
```

**Expected improvement**: Additional 30-50% speed boost

### 3. Keep Model Loaded (Already done in updated code)

The updated `OllamaAgent.java` now includes:
```java
body.put("keep_alive", "30m");  // Keeps model in memory
```

This prevents the 30-60 second model loading time on each request.

## Model Speed Comparison

| Model | Size | CPU Speed | Quality | Recommendation |
|-------|------|-----------|---------|----------------|
| llama3.2:1b | 1.3 GB | ⚡⚡⚡⚡ 15-30s | ⭐⭐⭐ Good | ✅ **Use This** |
| phi3:mini | 2.2 GB | ⚡⚡⚡ 30-45s | ⭐⭐⭐ Good | ✅ Alternative |
| llama3.2:3b | 2.0 GB | ⚡⚡ 45-90s | ⭐⭐⭐⭐ Better | ⚠️ Slower |
| deepseek-r1:7b | 4.7 GB | 🐌 5+ min | ⭐⭐⭐⭐⭐ Best | ❌ Too slow |

## Test Your Speed

```bash
# Benchmark current model
time docker exec -it ollama ollama run llama3.2:1b "Explain AI in one sentence"

# Should complete in 15-30 seconds
```

## If Still Slow: Enable GPU (Best Solution)

**For NVIDIA GPU users:**

```bash
# Stop current container
docker stop ollama
docker rm ollama

# Run with GPU support
docker run -d --gpus=all \
  -v ollama:/root/.ollama \
  -p 11434:11434 \
  --name ollama \
  ollama/ollama

# Pull model again
docker exec -it ollama ollama pull llama3.2:3b

# Test speed (should be 5-15 seconds now!)
time docker exec -it ollama ollama run llama3.2:3b "Hello"
```

**Expected with GPU**: 5-15 seconds (10-20x faster!)

## Hybrid Approach (Recommended for Production)

Use Ollama for simple tasks, Gemini for complex ones:

```java
@Service
public class SmartAiService {
    @Autowired
    private AiService aiService;
    
    public AiResponse chat(String message) {
        // Simple/short queries → Ollama (free, private)
        if (message.length() < 200) {
            return aiService.chat(AiChatRequest.builder()
                .model("llama3.2:1b")
                .message(message)
                .config(AiRequestConfig.builder()
                    .provider("OLLAMA")
                    .build())
                .build());
        }
        
        // Complex/long queries → Gemini (fast, accurate)
        return aiService.chat(AiChatRequest.builder()
            .message(message)
            .config(AiRequestConfig.builder()
                .provider("GEMINI")
                .build())
            .build());
    }
}
```

## Configuration for Best Performance

**application.properties:**
```properties
# Use Ollama with optimized settings
ai.active-provider=OLLAMA
ai.ollama.base-url=http://localhost:11434

# Fallback to Gemini if needed
ai.gemini.api-key=${GEMINI_API_KEY}
```

## Verify Improvements

After making changes, test:

```bash
# Test Ollama speed
curl -X POST http://localhost:11434/api/generate \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama3.2:1b",
    "prompt": "Say hello",
    "stream": false,
    "options": {
      "num_predict": 50,
      "num_ctx": 2048
    }
  }' | jq '.eval_duration'
```

## Summary

✅ **Do This Now:**
1. Use `llama3.2:1b` model
2. Increase Docker CPU/RAM
3. Rebuild SDK with updated OllamaAgent

✅ **Expected Result:**
- Before: 5 minutes
- After: 30-60 seconds (10x faster!)
- With GPU: 5-15 seconds (60x faster!)

✅ **Long-term:**
- Add GPU support for production
- Use hybrid approach (Ollama + Gemini)
- Monitor performance with logs

## Need More Help?

See detailed guides:
- [OLLAMA_PERFORMANCE_GUIDE.md](OLLAMA_PERFORMANCE_GUIDE.md) - Complete optimization guide
- [OLLAMA_SETUP.md](OLLAMA_SETUP.md) - Setup instructions

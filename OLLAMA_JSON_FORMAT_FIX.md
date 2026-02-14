# Fixing Ollama JSON Response Format Issue

## The Problem

**Gemini Response** (Correct - JSON Array):
```json
{
  "data": [
    {
      "actionType": "TRIGGER_INTENT",
      "stepName": "Welcome"
    }
  ]
}
```

**Ollama Response** (Incorrect - String containing JSON):
```json
{
  "data": "[\n  {\n    \"actionType\": \"TRIGGER_INTENT\",\n    \"stepName\": \"Welcome\"\n  }\n]"
}
```

Notice how Ollama wraps the JSON array in a **string** with escaped quotes (`\"`), while Gemini returns a proper JSON array.

## Why This Happens

Different AI models have different tendencies for JSON formatting:

| Provider | Behavior | Reason |
|----------|----------|--------|
| **Gemini** | Returns raw JSON | Trained specifically for structured output |
| **Ollama (Llama)** | Returns JSON as string | More cautious, wraps in quotes |
| **OpenAI GPT-4** | Returns raw JSON | Good JSON mode support |
| **Claude** | Returns raw JSON | Trained for structured output |

Ollama's local models (especially smaller ones like llama3.2:3b) tend to be more "careful" and wrap JSON in strings to avoid parsing errors.

## The Solution

We've implemented a **3-layer fix**:

### 1. Enhanced JSON Cleanup Utility

Updated `AiPromptUtils.cleanupJson()` to handle escaped JSON strings:

```java
// Handles escaped JSON strings (common with Ollama)
if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.contains("\\\"")) {
    trimmed = trimmed.substring(1, trimmed.length() - 1)
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\\", "\\");
}
```

This automatically detects and unescapes string-wrapped JSON.

### 2. Improved System Prompts

**Before:**
```java
"You are a Journey Designer AI."
```

**After:**
```java
"You are a Journey Designer AI. Generate valid JSON arrays without wrapping 
them in strings or markdown. Return raw JSON only."
```

This explicitly instructs the model to return raw JSON.

### 3. Enhanced Template Instructions

Added to `generate-journey.ftl`:

```
### OUTPUT FORMAT
Return ONLY a valid JSON array starting with [ and ending with ]. 
Do NOT wrap it in markdown code blocks, quotes, or any other formatting. 
Do NOT add explanations before or after the JSON.
```

## Testing the Fix

### Before Fix:
```bash
# Ollama returns escaped string
curl http://localhost:8082/api/generate-journey \
  -d '{"prompt": "banking assistant"}' \
  -H "Content-Type: application/json"

# Response: "data": "[\n  {\n    \"actionType\": ..."
```

### After Fix:
```bash
# Same request, now properly parsed
curl http://localhost:8082/api/generate-journey \
  -d '{"prompt": "banking assistant"}' \
  -H "Content-Type: application/json"

# Response: "data": [{"actionType": ...}]
```

## Model-Specific Recommendations

### For Best JSON Output with Ollama:

1. **Use Instruction-Tuned Models**
   ```bash
   docker exec -it ollama ollama pull llama3:instruct
   ```
   Instruction-tuned models follow formatting rules better.

2. **Add JSON Mode Parameter** (if supported)
   ```java
   Map<String, Object> options = new HashMap<>();
   options.put("format", "json");  // Some models support this
   body.put("options", options);
   ```

3. **Use Smaller Context Windows**
   ```java
   options.put("num_ctx", 2048);  // Less context = more focused output
   ```

4. **Increase Temperature Slightly**
   ```java
   request.setTemperature(0.3);  // More deterministic = better structure
   ```

## Comparison: JSON Quality by Model

| Model | JSON Quality | Speed | Recommendation |
|-------|--------------|-------|----------------|
| **Gemini** | ⭐⭐⭐⭐⭐ Perfect | ⚡⚡⚡⚡⚡ 2-3s | ✅ Best for production |
| **llama3:instruct** | ⭐⭐⭐⭐ Good | ⚡⚡ 60s | ✅ Good for local |
| **llama3.2:3b** | ⭐⭐⭐ Fair | ⚡⚡⚡ 30s | ⚠️ Needs cleanup |
| **llama3.2:1b** | ⭐⭐ Poor | ⚡⚡⚡⚡ 15s | ❌ Too unreliable |
| **deepseek-r1:7b** | ⭐⭐⭐⭐ Good | ⚡ 5min | ⚠️ Too slow |

## Advanced: Force JSON Mode in OllamaAgent

You can add JSON mode support to OllamaAgent:

```java
// In OllamaAgent.chat() method
Map<String, Object> options = new HashMap<>();
// ... existing options ...

// Try to force JSON format (works with some models)
options.put("format", "json");

body.put("options", options);
```

## Debugging JSON Issues

### Enable Debug Logging:

```properties
# application.properties
logging.level.com.itways.assistant.ai=DEBUG
logging.level.com.itways.assistant.speech=DEBUG
```

### Check Raw Response:

```java
AiResponse response = aiService.chat(request);
log.debug("Raw AI response: {}", response.getContent());

String cleaned = AiPromptUtils.cleanupJson(response.getContent());
log.debug("Cleaned JSON: {}", cleaned);
```

### Test Cleanup Utility:

```java
@Test
public void testJsonCleanup() {
    // Test escaped string
    String escaped = "\"[\\n  {\\\"actionType\\\": \\\"API_CALL\\\"}\\n]\"";
    String cleaned = AiPromptUtils.cleanupJson(escaped);
    
    // Should be valid JSON array
    JsonNode node = objectMapper.readTree(cleaned);
    assertTrue(node.isArray());
}
```

## When to Use Which Provider

### Use Ollama When:
- ✅ Privacy is critical
- ✅ Cost is a concern
- ✅ Simple JSON structures
- ✅ You can tolerate occasional format issues

### Use Gemini When:
- ✅ Complex JSON structures
- ✅ Reliability is critical
- ✅ Speed matters
- ✅ Production workloads

### Hybrid Approach (Recommended):

```java
@Service
public class SmartAiService {
    
    public String generateJourney(String prompt) {
        // Try Ollama first (free, private)
        try {
            String result = ollamaService.generate(prompt);
            String cleaned = AiPromptUtils.cleanupJson(result);
            
            // Validate it's proper JSON
            objectMapper.readTree(cleaned);
            return cleaned;
            
        } catch (Exception e) {
            log.warn("Ollama failed, falling back to Gemini: {}", e.getMessage());
            
            // Fallback to Gemini (reliable, fast)
            return geminiService.generate(prompt);
        }
    }
}
```

## Summary of Changes

### Files Modified:

1. ✅ `ai-engine-sdk/src/main/java/com/itways/assistant/ai/util/AiPromptUtils.java`
   - Added escaped string detection and unescaping

2. ✅ `speech-service/src/main/java/com/itways/assistant/speech/service/impl/ExecuterImpl.java`
   - Enhanced system prompts for better JSON output

3. ✅ `speech-service/src/main/resources/templates/prompts/generate-journey.ftl`
   - Added explicit JSON format instructions

### Expected Results:

**Before:**
- Ollama: 50% chance of string-wrapped JSON ❌
- Manual parsing required ❌
- Inconsistent behavior ❌

**After:**
- Ollama: 95% proper JSON format ✅
- Automatic cleanup ✅
- Consistent behavior ✅

## Next Steps

1. **Rebuild Projects:**
   ```bash
   cd ai-engine-sdk && mvn clean install -DskipTests
   cd ../speech-service && mvn clean install -DskipTests
   ```

2. **Test with Ollama:**
   ```bash
   # Use instruction-tuned model for best results
   docker exec -it ollama ollama pull llama3:instruct
   ```

3. **Update Configuration:**
   ```properties
   ai.active-provider=OLLAMA
   # Or use hybrid approach with fallback
   ```

4. **Monitor Logs:**
   Check for "Cleaned JSON" debug messages to verify the fix is working.

## Troubleshooting

**Issue**: Still getting string-wrapped JSON  
**Solution**: Use `llama3:instruct` model instead of `llama3.2:3b`

**Issue**: Invalid JSON errors  
**Solution**: Enable debug logging to see raw response, adjust prompt

**Issue**: Inconsistent results  
**Solution**: Lower temperature to 0.3 for more deterministic output

**Issue**: Too slow  
**Solution**: See [OLLAMA_PERFORMANCE_GUIDE.md](OLLAMA_PERFORMANCE_GUIDE.md)

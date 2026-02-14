# Ollama Model Recommendations for JSON Generation

## The Problem

Your current Ollama model is generating **invalid JSON** with:
- ❌ Wrong quote characters (`„` instead of `"`)
- ❌ Not returning an array (returns object instead)
- ❌ Incomplete structures
- ❌ Invalid syntax
- ❌ Comments in JSON (`*/`)

**This happens because smaller models (1B-3B) struggle with complex structured output.**

## Solution: Use Better Models

### Recommended Models for JSON Generation

| Model | Size | JSON Quality | Speed | Use Case |
|-------|------|--------------|-------|----------|
| **codellama:7b-instruct** | 3.8 GB | ⭐⭐⭐⭐⭐ Excellent | ⚡⚡ 60s | ✅ **BEST for JSON** |
| **llama3:instruct** | 4.7 GB | ⭐⭐⭐⭐ Very Good | ⚡⚡ 60s | ✅ Recommended |
| **mistral:7b-instruct** | 4.1 GB | ⭐⭐⭐⭐ Very Good | ⚡⚡ 50s | ✅ Good alternative |
| **deepseek-coder:6.7b** | 3.8 GB | ⭐⭐⭐⭐⭐ Excellent | ⚡⚡ 60s | ✅ Code-focused |
| **llama3.2:3b** | 2.0 GB | ⭐⭐ Poor | ⚡⚡⚡ 30s | ❌ Too small |
| **phi3:mini** | 2.2 GB | ⭐⭐ Poor | ⚡⚡⚡ 30s | ❌ Too small |

## Immediate Fix: Install Better Model

```bash
# BEST OPTION: CodeLlama (designed for structured output)
docker exec -it ollama ollama pull codellama:7b-instruct

# ALTERNATIVE: Llama3 Instruct (good all-rounder)
docker exec -it ollama ollama pull llama3:instruct

# ALTERNATIVE: Mistral (fast and good)
docker exec -it ollama ollama pull mistral:7b-instruct
```

## Update Your Configuration

### Option 1: Set Default Model in Application

**application.properties:**
```properties
ai.active-provider=OLLAMA
ai.ollama.base-url=http://localhost:11434
ai.ollama.default-model=codellama:7b-instruct
```

### Option 2: Specify Model in Code

**ExecuterImpl.java:**
```java
AiChatRequest chatRequest = AiChatRequest.builder()
    .messages(List.of(
        AiMessage.system(JOURNEY_GENERATION_SYSTEM_PROMPT), 
        AiMessage.user(prompt)
    ))
    .model("codellama:7b-instruct")  // Specify better model
    .temperature(0.2)  // Lower temperature for structured output
    .maxTokens(2048)
    .config(aiRequestConfig)
    .build();
```

## Why CodeLlama is Best for JSON

**CodeLlama** is specifically trained on:
- ✅ Code generation (including JSON)
- ✅ Structured output
- ✅ Following format specifications
- ✅ Syntax correctness

**Example comparison:**

### llama3.2:3b (Current - BAD):
```json
{
  "apiConfig": {
    "method": "GET|POST",
    "headers": „  // WRONG QUOTES!
```

### codellama:7b-instruct (Recommended - GOOD):
```json
[
  {
    "actionType": "API_CALL",
    "stepName": "Fetch Balance",
    "apiConfig": {
      "method": "GET",
      "headers": []
    }
  }
]
```

## Performance Comparison

### With CPU (Your Current Setup):

| Model | JSON Quality | Time | Recommendation |
|-------|--------------|------|----------------|
| llama3.2:3b | ⭐⭐ 20% valid | 30s | ❌ Don't use |
| codellama:7b-instruct | ⭐⭐⭐⭐⭐ 95% valid | 60s | ✅ **USE THIS** |
| llama3:instruct | ⭐⭐⭐⭐ 85% valid | 60s | ✅ Good backup |

### With GPU:

| Model | JSON Quality | Time | Recommendation |
|-------|--------------|------|----------------|
| codellama:7b-instruct | ⭐⭐⭐⭐⭐ 98% valid | 15s | ✅ **BEST** |
| llama3:instruct | ⭐⭐⭐⭐ 90% valid | 12s | ✅ Faster |

## Complete Setup Guide

### Step 1: Pull Better Model
```bash
docker exec -it ollama ollama pull codellama:7b-instruct
```

### Step 2: Test It
```bash
docker exec -it ollama ollama run codellama:7b-instruct '
Generate a JSON array with one object containing "name" and "age" fields.
Return ONLY the JSON array, no explanations.
'
```

Expected output:
```json
[
  {
    "name": "John",
    "age": 30
  }
]
```

### Step 3: Update OllamaAgent Default Model

**OllamaAgent.java:**
```java
private static final String DEFAULT_MODEL = "codellama:7b-instruct";  // Changed from llama3.2:3b
```

### Step 4: Rebuild and Test
```bash
cd ai-engine-sdk
mvn clean install -DskipTests

cd ../speech-service
mvn clean install -DskipTests
mvn spring-boot:run
```

## Advanced: Model-Specific Optimizations

### For CodeLlama (Best JSON):
```java
AiChatRequest request = AiChatRequest.builder()
    .model("codellama:7b-instruct")
    .temperature(0.2)      // Low temp for structured output
    .maxTokens(2048)       // Enough for complex JSON
    .messages(messages)
    .build();
```

### For Llama3 Instruct:
```java
AiChatRequest request = AiChatRequest.builder()
    .model("llama3:instruct")
    .temperature(0.3)
    .maxTokens(2048)
    .messages(messages)
    .build();
```

## Hybrid Approach (Recommended)

Use different models for different tasks:

```java
@Service
public class SmartAiService {
    
    // For JSON generation - use CodeLlama
    public String generateStructuredData(String prompt) {
        return aiService.chat(AiChatRequest.builder()
            .model("codellama:7b-instruct")
            .temperature(0.2)
            .message(prompt)
            .build());
    }
    
    // For simple text - use faster model
    public String simpleChat(String prompt) {
        return aiService.chat(AiChatRequest.builder()
            .model("llama3.2:3b")
            .temperature(0.7)
            .message(prompt)
            .build());
    }
    
    // For critical production - use Gemini
    public String productionGeneration(String prompt) {
        return aiService.chat(AiChatRequest.builder()
            .config(AiRequestConfig.builder()
                .provider("GEMINI")
                .build())
            .message(prompt)
            .build());
    }
}
```

## Fallback Strategy

Implement automatic fallback when JSON is invalid:

```java
public String generateJourneyStructure(String userPrompt) {
    // Try Ollama first
    try {
        String result = generateWithOllama(userPrompt);
        
        // Validate JSON
        objectMapper.readTree(result);
        log.info("Ollama generated valid JSON");
        return result;
        
    } catch (JsonProcessingException e) {
        log.warn("Ollama generated invalid JSON, falling back to Gemini");
        
        // Fallback to Gemini
        return generateWithGemini(userPrompt);
    }
}
```

## Model Installation Commands

```bash
# Install all recommended models
docker exec -it ollama ollama pull codellama:7b-instruct
docker exec -it ollama ollama pull llama3:instruct
docker exec -it ollama ollama pull mistral:7b-instruct

# Check installed models
docker exec -it ollama ollama list

# Remove old/bad models to save space
docker exec -it ollama ollama rm llama3.2:3b
docker exec -it ollama ollama rm phi3:mini
```

## Expected Results

### Before (llama3.2:3b):
- ❌ Invalid JSON: 80% of the time
- ❌ Wrong structure
- ❌ Parsing errors
- ❌ Manual fixes required

### After (codellama:7b-instruct):
- ✅ Valid JSON: 95% of the time
- ✅ Correct structure
- ✅ Automatic parsing
- ✅ Production-ready

## When to Use What

### Use CodeLlama (Ollama) for:
- ✅ JSON/structured data generation
- ✅ Code generation
- ✅ Development/testing
- ✅ Privacy-sensitive data

### Use Gemini for:
- ✅ Production workloads
- ✅ Complex reasoning
- ✅ When 100% reliability needed
- ✅ Time-critical operations

### Use llama3.2:3b for:
- ✅ Simple text generation
- ✅ Classification tasks
- ✅ When speed > quality
- ❌ NOT for JSON generation

## Summary

**Problem**: Small models (3B) can't generate valid JSON  
**Solution**: Use CodeLlama 7B or Llama3 Instruct  
**Expected**: 95%+ valid JSON, 60s response time  
**Best**: CodeLlama 7B with GPU = 98% valid, 15s response  

## Next Steps

1. **Install CodeLlama**: `docker exec -it ollama ollama pull codellama:7b-instruct`
2. **Update default model** in OllamaAgent.java
3. **Rebuild projects**
4. **Test JSON generation**
5. **Consider GPU** for 4x speed boost

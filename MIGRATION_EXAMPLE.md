# Migration Example: Adding Ollama to Existing Service

This example shows how to update an existing service (like speech-service) to support Ollama.

## Before (Current Configuration)

Your `application.properties` currently looks like:

```properties
ai.active-provider=${SPEECH_PROVIDER:groq}
ai.api-keys.openai=${OPENAI_API_KEY:sk-...}
ai.api-keys.groq=${GROQ_API_KEY:gsk_...}
ai.api-keys.gemini=${GEMINI_API_KEY:AIza...}
ai.api-keys.claude=${CLAUDE_API_KEY:sk-ant-...}
ai.api-keys.mistral=${MISTRAL_API_KEY:S72w...}
```

## After (With Ollama Support)

Simply add the Ollama configuration:

```properties
# Choose your provider
ai.active-provider=${SPEECH_PROVIDER:OLLAMA}

# Existing cloud providers
ai.api-keys.openai=${OPENAI_API_KEY:sk-...}
ai.api-keys.groq=${GROQ_API_KEY:gsk_...}
ai.api-keys.gemini=${GEMINI_API_KEY:AIza...}
ai.api-keys.claude=${CLAUDE_API_KEY:sk-ant-...}
ai.api-keys.mistral=${MISTRAL_API_KEY:S72w...}

# New: Ollama local provider
ai.ollama.base-url=${OLLAMA_BASE_URL:http://localhost:11434}
```

## No Code Changes Required!

Your existing service code continues to work:

```java
@Service
public class ExecuterImpl implements ExecuterService {
    @Autowired
    private AiService aiService;  // Automatically uses configured provider

    @Override
    public IntentResult extractIntent(SpeechRequest request) {
        AiChatRequest aiRequest = AiChatRequest.builder()
            .messages(messages)
            .model("llama3.2:3b")  // Just specify Ollama model
            .build();
            
        AiResponse response = aiService.chat(aiRequest);
        // ... rest of your code
    }
}
```

## Environment Variable Override

Switch providers at runtime:

```bash
# Use Ollama
export SPEECH_PROVIDER=OLLAMA

# Use Groq
export SPEECH_PROVIDER=GROQ

# Use OpenAI
export SPEECH_PROVIDER=OPENAI
```

## Docker Compose Example

```yaml
version: '3.8'

services:
  ollama:
    image: ollama/ollama
    ports:
      - "11434:11434"
    volumes:
      - ollama:/root/.ollama
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:11434"]
      interval: 30s
      timeout: 10s
      retries: 3

  speech-service:
    build: ./speech-service
    ports:
      - "8082:8082"
    environment:
      - SPEECH_PROVIDER=OLLAMA
      - OLLAMA_BASE_URL=http://ollama:11434
      - DB_URL=jdbc:postgresql://postgres:5432/ai_assistant_db
    depends_on:
      ollama:
        condition: service_healthy

volumes:
  ollama:
```

## Testing the Integration

1. Start Ollama and pull a model:
```bash
docker exec -it ollama ollama pull llama3.2:3b
```

2. Update your environment:
```bash
export SPEECH_PROVIDER=OLLAMA
```

3. Start your service:
```bash
mvn spring-boot:run
```

4. Test the endpoint:
```bash
curl -X POST http://localhost:8082/api/speech/process \
  -H "Content-Type: application/json" \
  -d '{
    "text": "Book a flight to Paris",
    "model": "llama3.2:3b"
  }'
```

## Performance Comparison

| Provider | Latency | Cost | Privacy |
|----------|---------|------|---------|
| Ollama (local) | ~2-5s | Free | ✅ Full |
| Groq | ~0.5-1s | Free tier | ⚠️ Cloud |
| OpenAI | ~1-3s | $$ | ⚠️ Cloud |
| Claude | ~1-2s | $$ | ⚠️ Cloud |

## Best Practices

1. **Development**: Use Ollama for cost-free development
2. **Production**: Use cloud providers for scale and speed
3. **Hybrid**: Use Ollama for sensitive data, cloud for general queries
4. **Fallback**: Configure multiple providers for redundancy

```java
// Example: Try Ollama first, fallback to Groq
try {
    // Use Ollama
    response = aiService.chat(request);
} catch (Exception e) {
    // Fallback to cloud provider
    request.setConfig(AiRequestConfig.builder()
        .provider("GROQ")
        .build());
    response = aiService.chat(request);
}
```

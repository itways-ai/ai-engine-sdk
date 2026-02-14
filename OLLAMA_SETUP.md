# Ollama Local AI Setup Guide

This guide shows you how to use local Ollama models with the AI Engine SDK.

## Prerequisites

1. Docker installed and running
2. Ollama container running with models

## Step 1: Verify Ollama is Running

Check your installed models:
```bash
docker exec -it ollama ollama list
```

You should see output like:
```
NAME               ID              SIZE      MODIFIED
phi3:mini          4f2222927938    2.2 GB    23 minutes ago
llama3.2:3b        a80c4f17acd5    2.0 GB    About an hour ago
deepseek-r1:7b     755ced02ce7b    4.7 GB    2 hours ago
llama3:instruct    365c0bd3c000    4.7 GB    10 days ago
```

## Step 2: Configure Your Application

Add to your `application.properties`:

```properties
# Set Ollama as the active provider
ai.active-provider=OLLAMA

# Configure Ollama base URL (default: http://localhost:11434)
ai.ollama.base-url=http://localhost:11434

# Optional: Keep other providers configured for fallback
ai.groq.api-key=${GROQ_API_KEY:}
ai.openai.api-key=${OPENAI_API_KEY:}
```

Or in `application.yml`:

```yaml
ai:
  active-provider: OLLAMA
  ollama:
    base-url: http://localhost:11434
```

## Step 3: Use in Your Code

### Basic Chat Example

```java
@Service
public class ChatService {
    @Autowired
    private AiService aiService;

    public String chat(String userMessage) {
        AiChatRequest request = AiChatRequest.builder()
            .messages(List.of(
                AiMessage.builder()
                    .role("user")
                    .content(userMessage)
                    .build()
            ))
            .model("llama3.2:3b")  // Specify your installed model
            .temperature(0.7)
            .maxTokens(1000)
            .build();
            
        AiResponse response = aiService.chat(request);
        return response.getContent();
    }
}
```

### Using Different Models

```java
// Fast, efficient model for simple tasks
AiChatRequest quickRequest = AiChatRequest.builder()
    .model("phi3:mini")
    .message("Summarize this text...")
    .build();

// Reasoning-focused model for complex tasks
AiChatRequest reasoningRequest = AiChatRequest.builder()
    .model("deepseek-r1:7b")
    .message("Solve this logic puzzle...")
    .build();

// Instruction-tuned model
AiChatRequest instructRequest = AiChatRequest.builder()
    .model("llama3:instruct")
    .message("Write a function to...")
    .build();
```

## Step 4: Install Additional Models

To add more models to your Ollama instance:

```bash
# Pull a new model
docker exec -it ollama ollama pull llama3.1:8b

# List available models at ollama.com/library
# Popular options:
# - codellama:7b (code generation)
# - mistral:7b (general purpose)
# - neural-chat:7b (conversational)
```

## Model Selection Guide

| Model | Size | Best For | Speed |
|-------|------|----------|-------|
| phi3:mini | 2.2 GB | Quick responses, simple tasks | ⚡⚡⚡ |
| llama3.2:3b | 2.0 GB | General purpose, balanced | ⚡⚡⚡ |
| deepseek-r1:7b | 4.7 GB | Complex reasoning, analysis | ⚡⚡ |
| llama3:instruct | 4.7 GB | Following instructions, coding | ⚡⚡ |

## Troubleshooting

### Connection Refused Error
```
Ollama API Error: Connection refused. Make sure Ollama is running at http://localhost:11434
```

**Solution**: Verify Ollama container is running:
```bash
docker ps | grep ollama
```

If not running, start it:
```bash
docker run -d -v ollama:/root/.ollama -p 11434:11434 --name ollama ollama/ollama
```

### Model Not Found Error
```
Error: model 'llama3.2:3b' not found
```

**Solution**: Pull the model first:
```bash
docker exec -it ollama ollama pull llama3.2:3b
```

### Slow Response Times

**Solutions**:
- Use smaller models (phi3:mini, llama3.2:3b)
- Reduce maxTokens in your request
- Ensure Docker has sufficient resources allocated

## Benefits of Local Ollama

✅ **Privacy**: Data never leaves your machine  
✅ **Cost**: No API fees  
✅ **Speed**: No network latency  
✅ **Offline**: Works without internet  
✅ **Control**: Full control over model versions  

## Switching Between Providers

You can easily switch providers without code changes:

```properties
# Use Ollama for development
ai.active-provider=OLLAMA

# Use cloud provider for production
# ai.active-provider=GROQ
# ai.active-provider=OPENAI
# ai.active-provider=CLAUDE
```

## Advanced Configuration

### Custom Ollama Host

If Ollama is running on a different machine:

```properties
ai.ollama.base-url=http://192.168.1.100:11434
```

### Docker Compose Integration

```yaml
services:
  ollama:
    image: ollama/ollama
    ports:
      - "11434:11434"
    volumes:
      - ollama:/root/.ollama
    
  your-app:
    build: .
    environment:
      - AI_ACTIVE_PROVIDER=OLLAMA
      - AI_OLLAMA_BASE_URL=http://ollama:11434
    depends_on:
      - ollama

volumes:
  ollama:
```

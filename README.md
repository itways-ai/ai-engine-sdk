# AI Engine SDK

A lightweight, multi-provider Java SDK designed for seamless integration of AI capabilities (LLMs and Transcription) into Spring Boot applications. It provides a unified interface for interacting with various AI models while maintaining a clean, decoupled architecture.

## 🚀 Features

- **Multi-Provider Support**: Switch between OpenAI, Groq, Anthropic, Gemini, Mistral, or local Ollama models using a unified API.
- **Smart Transcription**: High-performance speech-to-text processing.
- **JSON Value Extractor**: Specialized utility for extracting nested data from AI-generated JSON responses with path support (e.g., `entities.user[0].name`).
- **Spring Boot Native**: Includes `@EnableAi` for instant auto-configuration and bean management.
- **Model-Agnostic DTOs**: Standardized request and response objects across all providers.

## 🛠 Installation

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.itways.assistant</groupId>
    <artifactId>ai-engine-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

## ⚙️ Configuration

Enable the SDK in your Spring Boot application:

```java
@SpringBootApplication
@EnableAi
public class YourApplication {
    public static void main(String[] args) {
        SpringApplication.run(YourApplication.class, args);
    }
}
```

Configure your AI providers in `application.yml`:

```yaml
ai:
  active-provider: OLLAMA  # Options: OPENAI, GROQ, CLAUDE, GEMINI, MISTRAL, OLLAMA
  openai:
    api-key: ${OPENAI_API_KEY}
  groq:
    api-key: ${GROQ_API_KEY}
  claude:
    api-key: ${CLAUDE_API_KEY}
  gemini:
    api-key: ${GEMINI_API_KEY}
  mistral:
    api-key: ${MISTRAL_API_KEY}
  ollama:
    base-url: http://localhost:11434  # Default Ollama URL
```

## 📖 Usage

### 1. Basic Chat
Use `AiService` to send prompts to the default or specific providers.

```java
@Service
public class ChatService {
    @Autowired
    private AiService aiService;

    public String askAi(String prompt) {
        AiChatRequest request = AiChatRequest.builder()
            .message(prompt)
            .build();
            
        AiResponse response = aiService.chat(request);
        return response.getContent();
    }
}
```

### 2. Audio Transcription
Transcribe audio files or byte arrays with minimal configuration.

```java
public String processAudio(byte[] audioData) {
    AiTranscriptionRequest request = AiTranscriptionRequest.builder()
        .audioData(audioData)
        .build();
        
    AiResponse response = aiService.transcribe(request);
    return response.getContent();
}
```

### 3. JSON Field Extraction
Extract specific data from complex AI responses without manual POJO mapping.

```java
@Autowired
private JsonValueExtractor extractor;

public void parseResponse(String aiJson) {
    // Example path access: "user.metadata[0].id"
    String userId = extractor.getValue(aiJson, "user.id");
    System.out.println("Extracted User ID: " + userId);
}
```

### 4. Using Local Ollama Models
Run AI models locally with Ollama for privacy and cost savings.

```java
// Configure in application.yml
ai:
  active-provider: OLLAMA
  ollama:
    base-url: http://localhost:11434

// Use in code - specify the model you have installed
AiChatRequest request = AiChatRequest.builder()
    .message("Explain quantum computing")
    .model("llama3.2:3b")  // or phi3:mini, deepseek-r1:7b, etc.
    .temperature(0.7)
    .build();
    
AiResponse response = aiService.chat(request);
```

**Available Ollama Models** (based on your installation):
- `llama3.2:3b` - Fast, efficient model (2.0 GB)
- `phi3:mini` - Microsoft's compact model (2.2 GB)
- `deepseek-r1:7b` - Reasoning-focused model (4.7 GB)
- `llama3:instruct` - Instruction-tuned model (4.7 GB)

To install more models: `docker exec -it ollama ollama pull <model-name>`

📘 **For detailed Ollama setup and configuration, see [OLLAMA_SETUP.md](OLLAMA_SETUP.md)**

## 🧩 Core Components

| Component | Responsibility |
|-----------|----------------|
| `AiService` | Main gateway for chat and transcription operations. |
| `AiAgent` | Interface for vendor-specific implementations (Groq, OpenAI, etc.). |
| `JsonValueExtractor` | Utility for path-based JSON traversal. |
| `@EnableAi` | Activates auto-configuration and initializes provider beans. |

## 📝 License

Copyright © 2024 ITWays. All rights reserved.

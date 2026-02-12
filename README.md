# AI Engine SDK

A flexible Spring Boot SDK for integrating multiple AI providers with a unified interface.

## Supported Providers

- **Groq** - Fast inference with Llama models
- **OpenAI** - GPT-4 and Whisper models
- **Anthropic** - Claude models
- **Google Gemini** - Gemini models
- **Mistral** - Mistral AI models

## Configuration

Add API keys to your `application.properties`:

```properties
# Active provider (GROQ, OPENAI, ANTHROPIC, GEMINI, MISTRAL)
ai.active-provider=GROQ

# API Keys
ai.groq.api-key=your-groq-key
ai.openai.api-key=your-openai-key
ai.anthropic.api-key=your-anthropic-key
ai.gemini.api-key=your-gemini-key
ai.mistral.api-key=your-mistral-key
```

Or using the new format:

```properties
ai.activeProvider=GROQ
ai.apiKeys.groq=your-groq-key
ai.apiKeys.openai=your-openai-key
ai.apiKeys.anthropic=your-anthropic-key
ai.apiKeys.gemini=your-gemini-key
ai.apiKeys.mistral=your-mistral-key
```

## Usage

### Enable AI in your Spring Boot application

```java
@SpringBootApplication
@EnableAi
public class YourApplication {
    public static void main(String[] args) {
        SpringApplication.run(YourApplication.class, args);
    }
}
```

### Inject and use AiService

```java
@Service
@RequiredArgsConstructor
public class YourService {
    
    private final AiService aiService;
    
    public String chat(String userMessage) {
        AiChatRequest request = AiChatRequest.builder()
            .messages(List.of(
                AiMessage.builder()
                    .role("user")
                    .content(userMessage)
                    .build()
            ))
            .build();
            
        AiResponse response = aiService.chat(request);
        return response.getContent();
    }
}
```

### Override provider per request

```java
AiChatRequest request = AiChatRequest.builder()
    .config(AiRequestConfig.builder()
        .provider("ANTHROPIC")
        .apiKey("override-key")
        .build())
    .messages(messages)
    .model("claude-3-5-sonnet-20241022")
    .temperature(0.7)
    .maxTokens(2000)
    .build();
```

## Provider-Specific Models

### Groq
- `llama-3.3-70b-versatile` (default)
- `mixtral-8x7b-32768`
- `whisper-large-v3` (transcription)

### OpenAI
- `gpt-4o` (default)
- `gpt-4-turbo`
- `gpt-3.5-turbo`
- `whisper-1` (transcription)

### Anthropic
- `claude-3-5-sonnet-20241022` (default)
- `claude-3-opus-20240229`
- `claude-3-sonnet-20240229`

### Gemini
- `gemini-2.0-flash-exp` (default)
- `gemini-1.5-pro`
- `gemini-1.5-flash`

### Mistral
- `mistral-large-latest` (default)
- `mistral-medium-latest`
- `mistral-small-latest`

## Audio Transcription

Only Groq and OpenAI support audio transcription:

```java
AiTranscriptionRequest request = AiTranscriptionRequest.builder()
    .audioData(audioBytes)
    .filename("audio.mp3")
    .language("en")
    .build();
    
AiResponse response = aiService.transcribe(request);
```

## Adding New Providers

1. Create a new class implementing `AiAgent` in `com.itways.assistant.ai.impl`
2. Implement `chat()` and `transcribe()` methods
3. Add a bean in `AiEngineAutoConfiguration`
4. Add API key property in configuration

Example structure:

```java
@RequiredArgsConstructor
public class NewProviderAgent implements AiAgent {
    private final String defaultApiKey;
    private final RestTemplate restTemplate = new RestTemplate();
    
    @Override
    public String getProvider() {
        return "NEWPROVIDER";
    }
    
    @Override
    public AiResponse chat(AiChatRequest request) {
        // Implementation
    }
    
    @Override
    public AiResponse transcribe(AiTranscriptionRequest request) {
        // Implementation or return error if not supported
    }
}
```

A lightweight, multi-provider Java SDK designed for seamless integration of AI capabilities (LLMs and Transcription) into Spring Boot applications. It provides a unified interface for interacting with various AI models while maintaining a clean, decoupled architecture.

## 🚀 Features

- **Multi-Provider Support**: Switch between OpenAI, Groq, Anthropic, or local models using a unified API.
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
  providers:
    openai:
      api-key: ${OPENAI_API_KEY}
      model: gpt-4
    groq:
      api-key: ${GROQ_API_KEY}
      model: llama3-70b-8192
  default-provider: openai
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

## 🧩 Core Components

| Component | Responsibility |
|-----------|----------------|
| `AiService` | Main gateway for chat and transcription operations. |
| `AiAgent` | Interface for vendor-specific implementations (Groq, OpenAI, etc.). |
| `JsonValueExtractor` | Utility for path-based JSON traversal. |
| `@EnableAi` | Activates auto-configuration and initializes provider beans. |

## 📝 License

Copyright © 2024 ITWays. All rights reserved.

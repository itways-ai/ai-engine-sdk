# AI Engine SDK — Full Documentation

> **Type:** Internal Java SDK / Library
> **Group:** `com.itways.assistant` | **Artifact:** `ai-engine-sdk` | **Version:** `1.0.0`
> **Java:** 21 | **Spring Boot:** 3.2.0

---

## 1. High-Level Overview

`ai-engine-sdk` is the **Unified AI Gateway** for the AI Assistant Platform.

**What it does:**
It abstracts the complexities of communicating with multiple different Large Language Models (LLMs) and Speech-To-Text providers. Instead of having messy HTTP calls to OpenAI, Groq, or Claude scattered across microservices, this SDK provides a single clean interface: `AiService`.

**Its role in the system:**
It ensures vendor-lock-in prevention. By changing a single line in a configuration file (or passing a provider in a request), the host application can seamlessly switch its brain from OpenAI to Mistral to Groq without rewriting any business logic.

**Who uses it:**
- **`speech-service`**: Uses it to transcribe raw audio files into text, and then uses it to determine "intents".
- **`journey-engine-sdk`**: Embeds this SDK to perform dynamic data extraction (`DATA_MAP` steps) during workflow execution.
- **`template-service`**: Uses it to conditionally generate or summarize template content.

---

## 2. Architecture / Structure

The SDK is built around the **Strategy Pattern** combined with Spring Boot's Auto-Configuration.

### Core Architecture
1. **The Contract (`AiAgent`)**: An interface declaring `chat()` and `transcribe()`.
2. **The Strategies**: Concrete implementations covering specific provider REST APIs (`OpenAiAgent`, `GroqAgent`, `MistralAgent`, `GeminiAgent`, `AnthropicAgent`).
3. **The Router (`AiService`)**: The main service bean. If a request specifically asks for "OPENAI", it pulls the `OpenAiAgent` from a Spring-managed Map. Otherwise, it falls back to the configured default (`@Primary`).
4. **Auto-Configuration (`AiEngineAutoConfiguration`)**: Bootstraps all agents dynamically by reading API keys from the host application's `application.properties`.

### Package Structure
```
com.itways.assistant.ai
├── config/             → AutoConfig, Factory mapping, and the Agent interface
├── dto/                → Standardized inputs/outputs (AiChatRequest, AiResponse)
├── impl/               → Concrete AI provider clients (OpenAiAgent, GroqAgent)
├── service/            → The main AiService Router & JSON extractors
└── util/               → String sanitizers
```

---

## 3. Public APIs / Methods

### 🔹 The Main Gateway: `AiService`

```java
@Autowired
private AiService aiService;
```

#### Method: `chat(AiChatRequest)`
Sends a prompt (and optional files/images) to the LLM and returns the text response.
```java
AiChatRequest request = AiChatRequest.builder()
    .messages(List.of(
        AiMessage.system("Extract the city from the text."),
        AiMessage.user("I want to fly to Paris")
    ))
    .config(new AiRequestConfig("GROQ", "llama-3")) // explicit routing
    .build();

AiResponse response = aiService.chat(request);
System.out.println(response.getContent()); // "Paris"
```

#### Method: `transcribe(AiTranscriptionRequest)`
Converts an audio byte array into text string.
```java
AiTranscriptionRequest request = AiTranscriptionRequest.builder()
    .audioData(file.getBytes())
    .filename("voice_memo.mp3")
    .language("en")
    .build(); // Uses default provider if config is null

AiResponse response = aiService.transcribe(request);
```

### 🔹 Utility: `JsonValueExtractor`
Because LLMs often return JSON inside Markdown code blocks (or with extra text), this utility safely traverses and extracts target keys using JSON Paths, ignoring the mess.
```java
// text = "```json\n{\"intent\":\"BOOK_FLIGHT\", \"entities\":{\"city\":\"Paris\"}}\n```"
String city = jsonValueExtractor.getValue(text, "entities.city");
```

### 🔹 Utility: `AiPromptUtils`
Helper methods, particularly `cleanupJson(String)`, which strips markdown backticks (` ```json ... ``` `) from LLM responses so Jackson can parse them properly.

---

## 4. Dependencies & SDK Usage

This SDK handles network boundaries directly over HTTP for performance and flexibility without relying on heavy external wrapper SDKs.

### 📦 EXTERNAL DEPENDENCY: `Apache HttpClient 5`
**Purpose/Usage:**
Standard `RestTemplate` struggles with complex `multipart/form-data` requests combined with rigid chunking behavior required by inference APIs (especially OpenAI's whisper audio APIs). The SDK explicitly imports `httpclient5` to handle complex raw file byte uploading reliably across different providers.

### 📦 INTERNAL MODULES: None
**Deliberate Isolation:** This SDK deliberately **avoids** depending on `common-lib`. This ensures that `ai-engine-sdk` remains purely agnostic, extremely lightweight, and doesn't drag in Spring Security or messaging rabbit holes when a simple service just wants to make an AI call.

---

## 5. Usage Examples

### Auto-Configuration Example (Host App)

To use the AI Engine, a host microservice just needs three things:

1. **Add to POM**:
```xml
<dependency>
    <groupId>com.itways.assistant</groupId>
    <artifactId>ai-engine-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

2. **Enable via Annotation**:
```java
@EnableAi // <--- Bootstraps all agents based on properties
@SpringBootApplication
public class VoiceServiceApplication { ... }
```

3. **Provide Keys in `application.properties`**:
```properties
ai.active-provider=GROQ
ai.openai.api-key=sk-proj-...
ai.groq.api-key=gsk_...
```

---

## 6. Known Issues / Limitations

| # | Issue | Severity | Details |
|---|---|---|---|
| 1 | **Key Initialization Safety** | ⚠️ Medium | The `AiEngineAutoConfiguration` attempts to instantiate *all* agents at boot time (OpenAI, Claude, Mistral, etc.). If an API key is missing entirely, the `Agent` objects may still be created with null tokens. The SDK relies on HTTP 401 errors from the provider rather than fast-failing context startup. |
| 2 | **Synchronous Blocking HTTP** | 💡 Low | Interactions with `aiService` are currently synchronous. Long-running AI generation (large models) will lock the calling thread until the HTTP response fully buffers. There is no `Flux` or streaming response interface yet. |

---

## 7. Quick Mental Model

Imagine `ai-engine-sdk` as a **Translation Switchboard Operator**.

- You (the Host App) want to talk to an expert across the world (an AI Model).
- You don't want to memorize 5 different phone-book protocols, area codes, and languages for OpenAI, Microsoft, and Google.
- Instead, you pick up a direct line (`AiService`) and just state your goal: "Translate this audio" or "Answer this question."
- The switchboard operator looks at your account defaults (e.g., "Use GROQ by default"), dials the exact right number, speaks the right machine language, and hands the final English answer back to you.

---

## 8. TL;DR

`ai-engine-sdk` is an embeddable internal utility providing a unified facade for interfacing with multiple LLMs and Speech APIs. Activated via `@EnableAi`, it auto-wires connection strategies for OpenAI, Groq, Mistral, Gemini, and Claude, allowing host services to blindly call `aiService.chat()` without writing boilerplate HTTP interactions or dealing with JSON cleanup.

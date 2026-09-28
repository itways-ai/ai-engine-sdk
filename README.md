# ai-engine-sdk

A small Spring library that gives a service one way to talk to several LLM providers, and a local embedding engine.

- Chat: every provider takes an `AiChatRequest` (messages, tools, files, temperature, max tokens) and returns an `AiResponse` (text, tool calls, usage, model), or an `AiError` when the call failed.
- Transcription: `AiTranscriptionRequest` (audio bytes) gives back an `AiResponse` with the transcript.
- Embeddings: `LocalEmbeddingEngine` produces vectors through a local Ollama.

Who uses it: `speech-service` and `journey-engine-sdk`. Both depend on `com.itways.assistant:ai-engine-sdk:1.1.0`. `docker/java/Dockerfile` installs it from the workspace before building them.

## Using it

Add `@EnableAi` to a configuration class, or rely on the auto-configuration entry in `META-INF/spring/…AutoConfiguration.imports`. Then inject `AiService` and name the provider on each request:

```java
AiResponse reply = aiService.chat(AiChatRequest.builder()
        .config(AiRequestConfig.builder().provider("CLAUDE").apiKey(key).model(accountModel).build())
        .messages(List.of(AiMessage.system("Be brief."), AiMessage.user("Hello")))
        .build());
```

API keys are not configured in the library. Each request carries the key of the account it runs for, in `AiRequestConfig.apiKey`.

## Agents

| Provider (`config.provider`) | Class | Endpoint | Chat default model | Transcription | Images |
|---|---|---|---|---|---|
| `CLAUDE` | `AnthropicAgent` | `api.anthropic.com/v1/messages` | `claude-opus-5` | no | no |
| `OPENAI` | `OpenAiAgent` | `api.openai.com/v1/chat/completions` | `gpt-4o` | `whisper-1` | no |
| `GROQ` | `GroqAgent` | `api.groq.com/openai/v1/chat/completions` | `openai/gpt-oss-120b` | `whisper-large-v3` | yes, via `qwen/qwen3.8-27b` |
| `GEMINI` | `GeminiAgent` | `generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` | `gemini-3.5-flash-lite` | no | no |
| `MISTRAL` | `MistralAgent` | `api.mistral.ai/v1/chat/completions` | `mistral-small-2506` | no | no |

`OpenAiToolFormat` holds the tool-calling wire format that OpenAI, Groq and Mistral share.

### Which model is used

Each agent picks the model in this order (see `AbstractAiAgent.getEffectiveModel`):

1. `AiChatRequest.model`, when the caller names one for this request;
2. `AiRequestConfig.model`, the model the account configured;
3. the agent default in the table above.

The default is a last resort. Providers retire models, and when that happens every account without its own model breaks at once. Accounts should configure a model.

### Provider notes

- **Claude**
  - Current models (Claude Opus 5, Opus 4.7/4.8, Sonnet 5, Fable) reject `temperature`, `top_p` and `top_k` with a 400. The agent sends `temperature` only to older ids that accept it: `claude-3*` and `claude-{opus,sonnet,haiku}-4` up to `-4-6`. For any other model it drops the value and logs once at DEBUG.
  - The request always carries `anthropic-version: 2023-06-01` and `max_tokens`. When the request sets no `maxTokens`, it defaults to 16000 (`ai.anthropic.default-max-tokens`). Current models think by default, and thinking counts against `max_tokens`, so the old 4096 could cut answers off. A reply that stops at `max_tokens` is still returned, and a WARN is logged.
  - System messages at the start of the conversation, plus `systemPrompt`, go in the top-level `system` field.
  - A trailing plain assistant message is not sent, because current models reject assistant prefill.
  - For `claude-opus-5*`, `claude-fable-5*` and `claude-mythos-5*` the request opts into the API's server-side refusal fallback: header `anthropic-beta: server-side-fallback-2026-07-01` with `"fallbacks": "default"`. A request the model declines is then rerun on Anthropic's recommended substitute. The `fallbacks` field is never sent without that header.
  - A refusal that still comes back is logged at WARN and returned as an error of kind `REFUSED`, with `stop_details.category` in `error.category` (see Errors). `AnthropicAgent.REFUSAL_PREFIX` is deprecated and no longer produced.
- **Groq**
  - A request with images is switched to the vision model only when nobody chose a model. A model chosen on the request or by the account is kept.
  - Images are sent as base64 `data:` URLs. Text files are inlined into the message.
  - Groq's vision limits: 3 images per request, 20 MB per request.
- **Gemini**: the API key is sent in the `x-goog-api-key` header. It is never put in the URL, because URLs show up in exception messages and logs.

### Errors

The agents do not throw on provider failures. They return an `AiResponse` with `error` set and `content` null (since 1.1.0; before that the error text was the content). Check `response.isError()` before reading `content`:

```java
AiResponse reply = aiService.chat(request);
if (reply.isError()) {
    log.warn("AI call failed: {}", reply.getError().summary());   // e.g. RATE_LIMITED (GROQ 429)
    // use your own fallback; never show reply.getError().getMessage() to an end user
}
```

`AiError` has `kind`, `provider`, `providerStatus` (the HTTP status when the provider answered with one), `category` (a refusal's reason) and `message` (the provider's one-line explanation, with anything key-shaped removed and capped at 300 characters). `isRetryable()` is true for `RATE_LIMITED`, `TIMEOUT` and `UNAVAILABLE`.

| Kind | When |
|---|---|
| `RATE_LIMITED` | HTTP 429 |
| `AUTH` | 401, 403, Gemini's 400 `API_KEY_INVALID`, or no API key on the request |
| `REFUSED` | Claude `stop_reason: refusal` (category = `stop_details.category`); OpenAI-compatible `message.refusal` (category `refusal`) or `finish_reason: content_filter` with nothing left to show (OpenAI, Groq, Mistral); Gemini `promptFeedback.blockReason` or a blocked `finishReason` (`SAFETY`, `RECITATION`, …) with no text |
| `TIMEOUT` | read or connect timeout, 408, 504 |
| `UNAVAILABLE` | other 5xx (including Anthropic's 529), connection refused, unknown host |
| `BAD_REQUEST` | other 4xx (400, 404, 413, 422 …); transcription asked of an agent that has none |
| `OTHER` | an empty or unreadable response |

A refusal keeps `model` and `usage`, because the call was made and billed.

## Configuration properties

| Property | Default | Meaning |
|---|---|---|
| `ai.anthropic.default-model` | `claude-opus-5` | Claude model used when neither the request nor the account names one |
| `ai.anthropic.default-max-tokens` | `16000` | Claude output cap (thinking included) when the request sets none |
| `ai.anthropic.server-side-fallback` | `true` | Opt Opus 5 / Fable requests into the server-side refusal fallback |
| `ai.groq.vision-model` | `qwen/qwen3.8-27b` | Groq model for requests with images when no model was chosen |
| `ai.embedding.ollama-url` | `http://localhost:11434` | Ollama base URL for `LocalEmbeddingEngine` |

The shared `aiRestTemplate` has fixed timeouts: connect 15 s, response 120 s, pool wait 10 s. The pool allows 50 connections in total and 20 per route.

## Embeddings

`LocalEmbeddingEngine` calls Ollama's `/api/embeddings` with the model `granite-embedding:278m`, which gives 768 dimensions.

- `modelId()` returns the model name. Store it next to each vector, because vectors from different models cannot be compared.
- `embedBatch` sends the texts in mini-batches of 32.

## Build and test

The library has no Spring Boot parent. It pins Spring Boot 3.2.2, like the other modules, and needs JDK 21. The host JDK breaks Lombok, so build in the container:

```bash
docker run --rm -v "$PWD":/src -v ~/.m2:/m2 -w /src maven:3.9-eclipse-temurin-21 \
  mvn -B -Dmaven.repo.local=/m2 clean install
```

`mvn install` puts `ai-engine-sdk-1.1.0.jar` in the local repository. Build it before `journey-engine`, then build `speech-service`.

The tests are under `src/test/java/com/itways/assistant/ai/service/impl/`:

- One test class per agent. Each binds a `MockRestServiceServer` to the agent's `RestTemplate` and checks:
  - the request: URL, headers, default and overridden model, sampling parameters, tools, and image parts for Groq;
  - response parsing: text, tool calls, usage;
  - that 401, 429, 500 and 503 become an error reply rather than an exception.
- `ProviderErrorMappingTest` runs every agent against a stub provider on a loopback `HttpServer` (the agents' fixed URLs are rewritten to it) and checks that 429, 401, a refusal, 5xx and a timeout each become the right `AiError` kind with `content` null and no key in the message.
- `LocalEmbeddingEngineTest` runs the real langchain4j client against a stub Ollama on a loopback port.

No test calls a real provider or needs an API key.

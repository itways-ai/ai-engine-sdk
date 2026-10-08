# ai-engine-sdk

A small Spring library that gives a service one way to talk to several LLM providers, and a local embedding engine.

- Chat: every provider takes an `AiChatRequest` (messages, tools, files, temperature, max tokens) and returns an `AiResponse` (text, tool calls, usage, model), or an `AiError` when the call failed.
- Transcription: `AiTranscriptionRequest` (audio bytes) gives back an `AiResponse` with the transcript.
- Embeddings: `LocalEmbeddingEngine` produces vectors through a local Ollama.

Who uses it: `conversation-service` and `journey-engine-sdk`. Both take `com.itways.assistant:ai-engine-sdk` at the version `platform-bom` pins (1.3.0). `docker/java/Dockerfile` installs it from the workspace before building them.

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
| `OLLAMA` | `OllamaAgent` | `{ai.ollama.base-url}/v1/chat/completions` (self-hosted; only when the property is set) | `qwen3:8b` | no | no (answered "unsupported") |

`OpenAiToolFormat` holds the tool-calling wire format that OpenAI, Groq, Mistral and Ollama share.

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
- **Ollama** (a local open-source model, e.g. Qwen3 8B in the native Ollama app)
  - The agent exists only when `ai.ollama.base-url` has text (`OllamaConfiguredCondition`); unset or blank, a request for `OLLAMA` is "not supported". The base URL may end in `/` or `/v1`.
  - No key: nothing is sent in `Authorization`, whatever `AiRequestConfig.apiKey` holds.
  - Its own pooled HTTP client, not `aiRestTemplate`: connect 5 s, read and response timeout `ai.ollama.read-timeout` (default 120 s), because a local model is slow and the first call after a while also loads it.
  - Tool calls work as for OpenAI (`OpenAiToolFormat`). A null message `content` is sent as `""`, which Ollama requires outside a tool turn. `stream` is always `false`.
  - The default model is `qwen3:8b` with thinking off. `qwen2.5:7b` switched into Polish, Thai or Chinese mid-answer on Arabic questions; Qwen3 8B without thinking answered in clean Arabic. Any pulled model still works when the request or the account names it.
  - Thinking: every call sends `"reasoning_effort"` from `ai.ollama.reasoning-effort` (default `none`, which switches Qwen3's thinking off; blank sends nothing). On `/v1/chat/completions` Ollama ignores `"think": false`, so `reasoning_effort` is the switch. Any `<think>…</think>` block that still comes back is stripped from the content, with the whitespace after it.
  - Images and transcription are answered `BAD_REQUEST` "OLLAMA does not support images / audio transcription", without a call. A model that is not pulled is Ollama's 404 (`BAD_REQUEST`, message "model … not found, try pulling it first"); Ollama not running is `UNAVAILABLE`.
  - Ollama's OpenAI endpoint takes no `num_ctx`: a prompt longer than the server's context length is cut from the start. Set the context length in the Ollama app (or `OLLAMA_CONTEXT_LENGTH`) to fit the longest prompt.
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
| `ai.ollama.base-url` | (unset) | Ollama for chat (provider `OLLAMA`), e.g. `http://host.docker.internal:11434`; unset or blank = no Ollama agent |
| `ai.ollama.read-timeout` | `120s` | Time one Ollama chat call may take (`90s`, `PT2M`, or milliseconds) |
| `ai.ollama.default-model` | `qwen3:8b` | Ollama model used when neither the request nor the account names one |
| `ai.ollama.reasoning-effort` | `none` | Sent as `reasoning_effort` on every Ollama chat call; `none` switches a thinking model's thinking off, blank sends nothing |
| `ai.embedding.ollama-url` | `http://localhost:11434` | Ollama base URL for `LocalEmbeddingEngine` |
| `ai.embedding.model` | `granite-embedding:278m` | Embedding model; `modelId()` returns it |
| `ai.embedding.dimensions` | `768` | Size every returned vector must have (match the vector columns) |
| `ai.embedding.send-dimensions` | `false` | Also ask Ollama for that size (`"dimensions"` on `/api/embed`); `modelId()` becomes `model@dimensions` |
| `ai.embedding.query-prefix` | (none) | Put before every text `embedQuery` / `embedQueries` embed (e.g. `query: `); a missing trailing space is added |
| `ai.embedding.batch-size` | `32` | Texts per `/api/embed` call |
| `ai.embedding.truncate` | `false` | Let Ollama cut a text longer than the model's context instead of refusing it |
| `ai.embedding.connect-timeout-ms` | `5000` | Connect timeout for Ollama calls |
| `ai.embedding.read-timeout-ms` | `60000` | Time one Ollama call may take, including a cold model load (~7 s) |

The shared `aiRestTemplate` has fixed timeouts: connect 15 s, response 120 s, pool wait 10 s. The pool allows 50 connections in total and 20 per route. `OllamaAgent` has its own client (see Provider notes).

## Embeddings

`LocalEmbeddingEngine` turns text into vectors through a local Ollama, with the model `granite-embedding:278m` (768 dimensions, context 512 tokens) unless configured otherwise.

- `embed(text)` returns one vector; `embedBatch(texts)` returns one per text, in the same order.
- Both call `POST /api/embed` with `{"model", "input": [...], "truncate"}`, at most `ai.embedding.batch-size` (32) texts per call, so 64 passages cost two calls. Before 1.3.0 every text was its own `/api/embeddings` call.
- An Ollama older than 0.3.0 has no `/api/embed` and answers `404 page not found`. The engine then embeds one text per call through `/api/embeddings` (langchain4j) until it restarts. A 404 for a missing model (JSON `error`) is an error, not a reason to switch.
- Every vector has `ai.embedding.dimensions` components and unit length, so a dot product of two vectors is their cosine. Vectors stored before 1.3.0 point the same way but are not unit length: compare with cosine, not a raw dot product.
- `modelId()` returns the model name. Store it next to each vector, because vectors from different models cannot be compared.
- Asked dimensions and a query prefix (1.3.0). A model trained to be cut short (Matryoshka, e.g. `snowflake-arctic-embed2`) can be asked for fewer components: `"dimensions"` goes out on `/api/embed`, and `modelId()` is then `model@dimensions` (`snowflake-arctic-embed2@768`), since the same model at another size is another vector space. On the old per-text path the engine cuts the vector itself and normalises it. A model trained with a query instruction gets it from `embedQuery(text)` / `embedQueries(texts)` only; `embed` / `embedBatch` embed passages as they are.
- A second engine next to the Spring bean, with its own model, size and prefix but the same Ollama, batch size, truncation and timeouts: `engine.toBuilder().model("snowflake-arctic-embed2").askedDimensions(768).queryPrefix("query: ").build()`. `LocalEmbeddingEngine.builder(baseUrl)` starts from the defaults. conversation-service builds its knowledge-base embedder this way; intents keep the bean (granite).
- No automatic retries. Timeouts: connect 5 s, read 60 s.

Failures are unchecked `EmbeddingException`s; the message never contains the embedded text:

| Exception | When | What the caller can do |
|---|---|---|
| `EmbeddingInputTooLongException` | A text is over the model's context (Ollama: "the input length exceeds the context length"). `getIndex()` is the text's position in the list, `getLength()` its length in characters. For a batch the engine asks for the texts one at a time to find it. | Split or shorten that text, or set `ai.embedding.truncate=true` (Ollama then embeds only the text's beginning) |
| `EmbeddingDimensionException` | A vector's size is not `ai.embedding.dimensions` (`getExpected()`, `getActual()`) | Fix the configuration: the model and the dimensions do not match |
| `EmbeddingException` | Anything else: unreachable, timed out, an Ollama error, fewer vectors than texts | Treat embedding as unavailable for now |

A null or blank text is an `IllegalArgumentException` before any call.

## Build and test

The parent is `com.itways:platform-parent` 2.2.0 (from `common-lib`): it sets Java 21, manages the versions (Spring Boot 3.2.2) and runs JaCoCo, surefire and the sources jar. Only `langchain4j-ollama` carries its own version. The library brings `spring-web` for `RestTemplate`, not a web server; the consuming service chooses that.

Install `common-lib` first, then build with JDK 21 (newer JDKs break Lombok):

```bash
mvn -f ../common-lib/pom.xml install -DskipTests
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn clean install
```

`mvn install` puts `ai-engine-sdk-1.3.0.jar` in the local repository. Build it before `journey-engine`, then build `conversation-service`.

The tests are under `src/test/java/com/itways/assistant/ai/service/impl/`:

- One test class per agent. Each binds a `MockRestServiceServer` to the agent's `RestTemplate` and checks:
  - the request: URL, headers, default and overridden model, sampling parameters, tools, and image parts for Groq;
  - response parsing: text, tool calls, usage;
  - that 401, 429, 500 and 503 become an error reply rather than an exception.
- `OllamaAgentTest` runs `OllamaAgent` against a stub Ollama on a loopback `HttpServer`, through the client the auto-configuration builds: plain chat without a key, the default model and `reasoning_effort` (configured, blank), a `<think>` block stripped from the content, the account's model and sampling settings, tool declaration and a tool call back, a replayed tool turn, images and transcription unsupported, a missing model (404), 5xx and 429, an empty answer, the read timeout, Ollama not running, and the auto-configuration (no agent without a base URL, the read timeout, default model and reasoning effort properties).
- `ProviderErrorMappingTest` runs every agent against a stub provider on a loopback `HttpServer` (the agents' fixed URLs are rewritten to it) and checks that 429, 401, a refusal, 5xx and a timeout each become the right `AiError` kind with `content` null and no key in the message.
- `LocalEmbeddingEngineTest` runs the engine against a stub Ollama on a loopback port (real HTTP for `/api/embed` and, through langchain4j, `/api/embeddings`): calls of 32, order, the 404 fallback, a missing model, vector size, the too-long text named by position, timeouts, the Spring properties, asked dimensions and `model@dimensions`, the query prefix on queries only, a second engine from `toBuilder()`.
- `LocalEmbeddingEngineLiveTest` runs against a real Ollama and is skipped unless you name one: `mvn test -Dtest=LocalEmbeddingEngineLiveTest -Dollama.live-url=http://127.0.0.1:11434`. It only embeds; its knowledge-model test needs `snowflake-arctic-embed2` pulled too.

No test calls a real provider or needs an API key; the live Ollama test runs only when asked for.

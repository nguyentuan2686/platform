# Plan: Claude as the primary Q&A LLM provider (reading-tuvi)

Goal: natural Vietnamese answers for "Hỏi thầy", grounded only in the prompt's LÁ SỐ / THỜI VẬN / TƯ LIỆU blocks, from the Anthropic Messages API; Groq/Gemini stay as fallbacks, fake stays the local default.

## Decisions
- Raw HTTP (`POST https://api.anthropic.com/v1/messages`, `anthropic-version: 2026-10-21`) through the service's existing Spring `RestClient` + JDK client, like `OpenAiCompatibleLlmAdapter`; no SDK dependency. Non-streaming: the gateway and Q&A pipeline are request/response today and answers are short.
- Default model `claude-haiku-5-5` (`ANTHROPIC_MODEL`): the cheap tier ($0.10 in / $0.50 out per MTok for prompts <= 100K), follows instructions well, and a Q&A answer is ~2K input tokens. Effort `low` (`ANTHROPIC_EFFORT`), adaptive thinking left on (the model's default; effort is the lever). Thinking counts toward `max_tokens`, so the request sends `maxTokens + ANTHROPIC_THINKING_HEADROOM_TOKENS` (default 1024); answer length stays set by the prompt and checked by `AnswerFilter`.
- No `temperature` (Haiku 5.5 and API version 2026-10-21 reject sampling params).
- Prompt caching: one explicit `cache_control` breakpoint on the system block (fixed per topic and answer kind, shared across users); the per-request chart facts, passages and question come after it. `ANTHROPIC_PROMPT_CACHE=false` turns it off.
- Responses: text = concatenated `text` blocks (thinking blocks skipped). `stop_reason: refusal`, no text, or malformed JSON -> `LlmUnavailableException` (next provider). 429 -> `LlmRateLimitedException` (Retry-After seconds); 5xx/529 -> unavailable; 400/401/402/403/404 -> logged status + unavailable. Never logs key, prompt, question or answer.
- Cost: `LlmCompletion` gains cache write/read token counts; `PaidPricing` gains cache prices; the gateway prices each paid provider with its own `PaidPricing` (claude: `ANTHROPIC_*_USD_PER_MTOK`, budget `ANTHROPIC_MONTHLY_BUDGET_USD`, default 5). Over budget -> provider skipped -> Groq/Gemini.
- Tier: Claude is budgeted (`paid() == true`) but may serve free questions (`servesFreeTier()`, `ANTHROPIC_SERVE_FREE_TIER`, default true); the free global daily cap (500) and the monthly budget bound the spend.
- Order: prod `LLM_PROVIDERS=claude,groq,gemini`; a provider without key is skipped, so this is safe before the key exists.

## Steps (TDD)
1. Gateway: per-provider pricing, cache-token cost, `servesFreeTier` (tests first in `LlmGatewayTest`).
2. `AnthropicLlmAdapter` + `AnthropicSettings` with `LlmStubServer` tests: request shape, success, thinking blocks skipped, cache usage, 429, 5xx/529, timeout, refusal, client error log without key, no prompt text in logs.
3. `LlmConfiguration`: `claude` provider and pricing (tests in `LlmConfigurationTest`).
4. Config surfaces: compose, `.env.example`, prod `LLM_PROVIDERS`, runbooks (`llm-gateway.md`, `go-live.md`).
5. Secret (second PR, after the owner pastes the key): `ANTHROPIC_API_KEY?` in `secrets.tsv`, e2e env, sealed `reading-tuvi-secrets` (`scripts/seal-secrets.sh --only reading-tuvi-secrets`). These three must change together (`k8s-validate` compares their key sets).

# 3. LLM gateway inside reading-tuvi

Date: 2026-11-25

## Status
Accepted (fortune design spec in `docs/superpowers/specs/`, plan F1.4 in `docs/superpowers/plans/`)

## Context
The paid and free tử vi Q&A needs an LLM that answers in persona, grounded in the user's chart and the approved knowledge base. Constraints: near-zero cost at launch (free tiers first), a weak dev machine (2 cores / 12 GB), privacy (no identity or birth data may reach a provider), and CI that must never call a real provider.

Alternatives considered:
- A separate gateway service: one more container and deploy for a single consumer.
- Vector retrieval with pgvector and local ONNX embeddings: about 500 MB RAM; the 558-record corpus is already keyed and the chart selects the keys.
- Claude at runtime: rejected at first (no Claude/Anthropic API at runtime); reversed on 2026-11-28, see the amendment below.

## Decision
- The gateway is a Java library inside `reading-tuvi` (packages under `dev.platform.reading.tuvi`), not a new service.
- Providers sit behind one port with four adapters: `fake` (default locally and in CI; modes `ok`, `fail`, `unsafe`), `groq` and `gemini` (OpenAI-compatible, free tier, enabled only when their API key is set) and `paid` (an OpenAI-compatible fallback with a monthly USD budget). `LLM_PROVIDERS` is the ordered failover list.
- Retrieval is key-based: the chart picks knowledge keys for the topic; no pgvector in F1. Revisit when the corpus grows (F2/F3).
- The prompt carries only the persona text, `lang`, the topic label, Vietnamese palace facts, the selected knowledge snippets and the sanitized question. It never carries a name, email, UUID, birth data or chart hash. Question and answer text is stored AES-GCM encrypted (`QA_ENC_KEY`); logs carry ids, status, provider, latency and token counts only.
- Generation is complete-then-filter, then buffered sentence-level SSE (not raw token pass-through), so the safety layer sees the whole answer. The disclaimer is appended server-side.
- A paid question is charged (identity-svc internal credit API) after the guard and the chart, before the LLM. If no provider answers, the reading is retried for 24 h (`QA_RETRY_WINDOW`) and then refunded.

## Consequences
No new container and no new network hop. The cost is that LLM concerns (quotas, budget, retries) live in `reading-tuvi`; extract them only if a second consumer appears. Free-tier limits are tracked per deployment, so two deployments sharing a key must split `*_DAILY_REQUEST_LIMIT`. Keyed retrieval is more grounded than vector search at this corpus size but cannot answer topics the keys do not cover (those are refused as `insufficient` and refunded). The guard terms refuse some fair questions (for example a medical term inside a love question); that bias is deliberate. Real providers need keys, a terms/privacy review and a sample-answer read by the owner before launch (see `docs/runbooks/llm-gateway.md`).

## Amendment 2026-11-28: Claude as the primary provider
The owner reversed the "no Claude at runtime" alternative: free-tier answers read template-like, and the goal is natural Vietnamese answers grounded only in the prompt's blocks. A fifth adapter, `claude` (Anthropic Messages API, raw HTTP through the existing `RestClient`, no SDK), comes first in prod (`LLM_PROVIDERS=claude,gemini,groq`) and is skipped while `ANTHROPIC_API_KEY` is unset. It is budgeted like `paid` but with its own prices and monthly budget (`ANTHROPIC_*`), and by default also answers free questions. Over budget, rate-limited, refusing or down, it falls through to Groq/Gemini. The privacy rules above are unchanged: the prompt still carries no identity or birth data. Plan: `docs/superpowers/plans/2026-11-28-claude-llm-provider.md`; operations: `docs/runbooks/llm-gateway.md` §2a.

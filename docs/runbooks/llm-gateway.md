# Runbook: LLM gateway and Q&A (reading-tuvi)

Local runs use `LLM_PROVIDERS=fake`; no key is needed. Real keys only via Sealed Secrets or your shell, never in Git, chat or logs.

## 1. Owner to-dos before real providers (Tuấn)
None of these blocks the F1.4 merge; everything runs on the fake provider until done.
1. Create a Groq API key (console.groq.com) and a Gemini API key (Google AI Studio). Hand them over only as Sealed Secrets.
2. Read both free-tier terms on commercial use and on training with submitted data (spec section 11, section 15 cross-border transfer). If the free tier is not allowed commercially, enable `paid` instead.
3. Decide on a small paid fallback (Groq paid or Gemini paid billing account) and the monthly budget (`LLM_PAID_MONTHLY_BUDGET_USD`, default 5).
4. Verify the model ids and prices against the providers' current docs: `GROQ_MODEL`, `GEMINI_MODEL`, `LLM_PAID_MODEL`, `LLM_PAID_INPUT_USD_PER_MTOK`, `LLM_PAID_OUTPUT_USD_PER_MTOK`, and the free-tier limits `GROQ_DAILY_REQUEST_LIMIT`, `GEMINI_DAILY_REQUEST_LIMIT`.
5. Add the LLM providers to the privacy policy and the DPIA / cross-border transfer dossier.
6. Read sample answers produced with real keys before launch (spec section 11 item 3). The 150-question eval also needs real keys.

Follow-ups (not blocking):
- identity-svc "refund-before-consume tombstone": a refund that reaches identity-svc before a delayed consume of the same reading could leave the credit consumed. Today `QA_CONSUME_REFUND_GRACE` (default 2 min) delays such refunds to make this unlikely; a tombstone in identity-svc closes it.
- Q&A web UI and user notifications (F1.5b); Q&A metrics and alerts (platform Plan 3).

## 2. Enable a provider
Set the key, put the provider in the ordered list, then roll reading-tuvi.
- Claude (primary, Anthropic Messages API): `ANTHROPIC_API_KEY` in `reading-tuvi-secrets`, `LLM_PROVIDERS=claude,gemini,groq` (prod already lists `claude` first; it stays skipped with the WARN `llm provider claude disabled: no api key` until the key exists). See section 2a.
- Free: `GROQ_API_KEY`, `GEMINI_API_KEY`, `LLM_PROVIDERS=gemini,groq` (order = failover order: when Gemini hits `GEMINI_DAILY_REQUEST_LIMIT` or answers 429, Groq answers). A provider without a key is skipped.
- More fallbacks: `OPENROUTER_API_KEY`, `CEREBRAS_API_KEY`, `LLM_PROVIDERS=claude,gemini,groq,openrouter,cerebras`. See section 2b.
- Paid fallback: `LLM_PAID_API_KEY` (plus `LLM_PAID_BASE_URL`, `LLM_PAID_MODEL`, the two prices), `LLM_PROVIDERS=gemini,groq,paid`. Free chat never uses `paid`. When the month's spend reaches `LLM_PAID_MONTHLY_BUDGET_USD` the paid provider stops answering and paid questions fall into the retry path (section 4).
- Two deployments sharing one key count quota separately: set each `*_DAILY_REQUEST_LIMIT` to its share.
Check: ask one question per provider and read `llm_usage` (section 3). Logs show provider id, outcome, latency and token counts, never text.

## 2a. Claude (Anthropic)
How it behaves:
- `POST https://api.anthropic.com/v1/messages`, header `anthropic-version: 2026-10-21`, model `ANTHROPIC_MODEL` (default `claude-haiku-5-5`: the low-cost tier, about 2K input tokens per question), effort `ANTHROPIC_EFFORT` (default `low`). Adaptive thinking stays on, so each request asks for the answer budget plus `ANTHROPIC_THINKING_HEADROOM_TOKENS` (default 1024); answer length is still set by the prompt and checked by the output filter. No `temperature` (the model rejects it).
- Prompt caching: the system block (fixed per topic and answer kind) carries a 5-minute `cache_control` breakpoint; `ANTHROPIC_PROMPT_CACHE=false` turns it off. Hits show as `cache_read_tokens` in the `llm completed` log line.
- Claude is budgeted: cost per answer = plain input x `ANTHROPIC_INPUT_USD_PER_MTOK` (0.10) + cache writes x `ANTHROPIC_CACHE_WRITE_USD_PER_MTOK` (0.125) + cache reads x `ANTHROPIC_CACHE_READ_USD_PER_MTOK` (0.01) + output x `ANTHROPIC_OUTPUT_USD_PER_MTOK` (0.50), summed in `llm_usage.cost_micro_usd` for provider `claude`. When the month reaches `ANTHROPIC_MONTHLY_BUDGET_USD` (default 5, roughly 10,000 answers) Claude is skipped and Groq/Gemini answer. Change the four prices together with the model.
- Free questions also use Claude (`ANTHROPIC_SERVE_FREE_TIER=true`, default); the free global daily cap (`QA_FREE_GLOBAL_DAILY_CAP`) and the budget bound the spend. Set it to `false` to keep free chat on Groq/Gemini only.
- 429 (rate limit or the console spend limit) marks Claude exhausted (`Retry-After` capped at a day, else `LLM_RATE_LIMIT_BACKOFF`, default 1 h); 5xx/529, timeouts (`ANTHROPIC_TIMEOUT`, default 30 s), a `refusal` stop reason or an empty answer fall through to the next provider; 400/401/402/403/404 log `llm provider rejected request provider=claude status=<code>` (check the key, billing and model id). Keys, prompts, questions and answers are never logged.

Owner steps (Tuấn), only if Claude is wanted (today Hỏi thầy runs on the free Gemini and Groq keys; without this key Claude is skipped):
1. Sign in at https://console.anthropic.com (an organization for the business; add billing in Settings > Billing and buy a small prepaid credit, e.g. 5 USD).
2. Settings > Workspaces: create a workspace `xemtuvingay-prod` and give it a monthly spend limit a little above `ANTHROPIC_MONTHLY_BUDGET_USD` (e.g. 6 USD). This is the hard stop if the app's own budget ever fails. Also check Settings > Limits for the organization-wide limit.
3. Settings > API Keys > Create Key, workspace `xemtuvingay-prod`, name `reading-tuvi-prod`. Copy it once into a password manager.
4. On the sealing machine, add the line `ANTHROPIC_API_KEY=<key>` to `deploy/k8s/overlays/prod/secrets.local/reading-tuvi-secrets.env` (gitignored). Never paste the key into Git, an issue, a PR or a chat with Claude.
5. Tell Claude it is pasted. Claude then adds `ANTHROPIC_API_KEY?` to the `reading-tuvi-secrets` row of `secrets.tsv` and to the e2e env, runs `scripts/seal-secrets.sh --cert deploy/k8s/overlays/prod/sealed/sealing-cert.crt --only reading-tuvi-secrets` and opens the PR with the sealed file. After the deploy: ask one question and check `llm completed provider=claude` in the logs and `llm_usage` (section 3).
6. Add Anthropic to the privacy policy and the cross-border transfer dossier (as for Groq/Gemini, section 1 item 5), and read a few real answers before announcing.


## 2b. OpenRouter and Cerebras (OpenAI-compatible fallbacks)
Both use the same adapter as Groq/Gemini (`POST {base}/chat/completions`), so 429 backoff (`Retry-After`, else `LLM_RATE_LIMIT_BACKOFF`, default 1 h), failover on a cut-off reply (`finish_reason=length`) and `reasoning_effort` work the same. Every setting is `<PREFIX>_*` with prefix `OPENROUTER` or `CEREBRAS`: `_API_KEY`, `_BASE_URL`, `_MODEL`, `_DAILY_REQUEST_LIMIT`, `_REASONING_EFFORT`, `_PAID`, `_SERVE_FREE_TIER`. Prod order: `claude,gemini,groq,openrouter,cerebras`; a provider without a key is skipped with `llm provider <id> disabled: no api key`.
- OpenRouter: base `https://openrouter.ai/api/v1`, default model `google/gemma-4-31b-it:free` (free: 50 free-model requests a day without credits, so `OPENROUTER_DAILY_REQUEST_LIMIT` defaults to 45; raise it to ~950 after buying 10 USD of credits, which lifts the cap to 1000). No reasoning effort by default.
- Switch OpenRouter to a cheap paid model by env only, e.g. DeepSeek v4 flash: `OPENROUTER_MODEL=deepseek/deepseek-v4-flash`, `OPENROUTER_PAID=true`, `OPENROUTER_INPUT_USD_PER_MTOK` / `OPENROUTER_OUTPUT_USD_PER_MTOK` = the model's prices on https://openrouter.ai/models, `OPENROUTER_MONTHLY_BUDGET_USD` (default 2). The price defaults (1.00 in, 4.00 out) are deliberately high, so a forgotten price stops it early, not late. A paid OpenRouter has no daily cap by default and still answers free questions (`OPENROUTER_SERVE_FREE_TIER=false` keeps free chat off it); its spend is in `llm_usage.cost_micro_usd` for provider `openrouter`.
- Cerebras: base `https://api.cerebras.ai/v1`, `CEREBRAS_API_KEY`, default model `gpt-oss-120b` with `CEREBRAS_REASONING_EFFORT=low` (a reasoning model, like Groq's), free, `CEREBRAS_DAILY_REQUEST_LIMIT` default 900. Not keyed in prod yet: the owner pastes `CEREBRAS_API_KEY=<key>` into the gitignored `secrets.local/reading-tuvi-secrets.env`, then `CEREBRAS_API_KEY?` goes into `secrets.tsv`, the e2e env and the sealed file together.
- The OpenRouter and Cerebras default model ids were not probed (no probing with prod keys). After the first deploy with a key, check `llm completed provider=openrouter` (or `cerebras`) in the logs; a 404 or 400 logs `llm provider rejected request provider=<id> status=<code>`: list the ids with `GET {base}/models` and set `<PREFIX>_MODEL`.

## 3. Read `llm_usage`
Table `llm_usage` in the `reading_tuvi` database: one row per provider and day with `requests`, `prompt_tokens`, `completion_tokens` and `cost_micro_usd` (millionths of a USD; only the budgeted providers `claude` and `paid` have a cost). `llm_provider_state.exhausted_until` shows a provider currently skipped after a quota or rate-limit answer.
- Today vs limit: `select provider, requests, prompt_tokens, completion_tokens from llm_usage where day = current_date;` compare `requests` with `*_DAILY_REQUEST_LIMIT`.
- Paid spend this month: `select provider, sum(cost_micro_usd) / 1e6 as usd from llm_usage where provider in ('claude', 'paid') and day >= date_trunc('month', current_date) group by provider;` compare `claude` with `ANTHROPIC_MONTHLY_BUDGET_USD` and `paid` with `LLM_PAID_MONTHLY_BUDGET_USD`.
- Skipped providers: `select * from llm_provider_state where exhausted_until > now();` A 429 marks the provider exhausted for its `Retry-After` (capped at one day), or for `LLM_RATE_LIMIT_BACKOFF` (default `PT1H`) when there is none; providers reset quotas on their own clock (Gemini free tier: midnight Pacific).
- Which model answered or declined: logs `llm completed provider=...`, `qa answered ... provider=...`, `qa model declined ... reason=... provider=...` (retry job: `qa retry declined ... provider=...`).
- Never probe or evaluate prompts with the prod keys: free tiers share one quota per key, and a probe session used up prod's Gemini daily quota once (lesson 2026-11-28). Use a separate dev key, a handful of calls, request bodies from a UTF-8 file.

## 4. Stuck RETRYING / REFUND_PENDING rows
Status lives in `qa_reading.status`; the owner sees it at `GET /tuvi/qa/readings/{id}` (`status` plus the queued/refunded `notice`).
- `RETRYING`: a charged paid question that no provider could answer. The job (`QA_JOB_INTERVAL`, default 1 min) retries until `retry_until` (`QA_RETRY_WINDOW`, 24 h). Normal while providers are down. If it persists, check the providers (keys, quota, budget) and that `QA_JOBS_ENABLED=true`. `last_error` holds a code only (for example `all_providers_exhausted`).
- `REFUND_PENDING`: the window ended (or the answer was judged insufficient after charging) and the credit refund to identity-svc has not succeeded yet. The job retries it every interval. If it stays, identity-svc is unreachable or rejecting the call: check `IDENTITY_SVC_URL`, the shared `INTERNAL_API_TOKEN` and identity-svc logs. Refunds are idempotent per reading, so retrying is safe.
- `REFUNDED`: settled. The user sees the refunded notice and asks again with a new `reading_id`.
- Do not edit statuses by hand while the job runs; fix the dependency and let the job settle the row. With `QA_JOBS_ENABLED=false` nothing retries or refunds (debugging only).
- List stuck rows: `select id, status, attempts, next_attempt_at, retry_until, last_error from qa_reading where status in ('RETRYING','REFUND_PENDING') order by created_at;`
- The same job deletes free chats older than `QA_FREE_RETENTION` (90 days); paid readings stay until the user deletes them.

## 5. The `LLM_FAKE_MODE=fail` drill
Proves the retry path end to end without a real provider.
1. `LLM_FAKE_MODE=fail docker compose -f deploy/local/docker-compose.yml --profile app up -d --no-deps reading-tuvi` (recreates the container).
2. Log in (as `scripts/smoke-qa.sh` does), save a profile, and POST a PAID question: the stream is `event: meta` then `event: queued`, and the credit is consumed.
3. `GET /tuvi/qa/readings/{id}` shows `status: RETRYING`.
4. Restore `LLM_FAKE_MODE=ok` and recreate: within a job interval the row becomes `ANSWERED`. To see the refund instead, recreate with `QA_RETRY_WINDOW=PT1S`: the row goes `REFUND_PENDING`, then `REFUNDED`, and the credit is back.
`LLM_FAKE_MODE=unsafe` returns an answer the output filter must block, for checking that path.

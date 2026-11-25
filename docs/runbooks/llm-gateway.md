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
- Free: `GROQ_API_KEY`, `GEMINI_API_KEY`, `LLM_PROVIDERS=groq,gemini` (order = failover order). A provider without a key is skipped.
- Paid fallback: `LLM_PAID_API_KEY` (plus `LLM_PAID_BASE_URL`, `LLM_PAID_MODEL`, the two prices), `LLM_PROVIDERS=groq,gemini,paid`. Free chat never uses `paid`. When the month's spend reaches `LLM_PAID_MONTHLY_BUDGET_USD` the paid provider stops answering and paid questions fall into the retry path (section 4).
- Two deployments sharing one key count quota separately: set each `*_DAILY_REQUEST_LIMIT` to its share.
Check: ask one question per provider and read `llm_usage` (section 3). Logs show provider id, outcome, latency and token counts, never text.

## 3. Read `llm_usage`
Table `llm_usage` in the `reading_tuvi` database: one row per provider and day with `requests`, `prompt_tokens`, `completion_tokens` and `cost_micro_usd` (millionths of a USD; only `paid` has a cost). `llm_provider_state.exhausted_until` shows a provider currently skipped after a quota or rate-limit answer.
- Today vs limit: `select provider, requests, prompt_tokens, completion_tokens from llm_usage where day = current_date;` compare `requests` with `*_DAILY_REQUEST_LIMIT`.
- Paid spend this month: `select sum(cost_micro_usd) / 1e6 as usd from llm_usage where provider = 'paid' and day >= date_trunc('month', current_date);` compare with `LLM_PAID_MONTHLY_BUDGET_USD`.
- Skipped providers: `select * from llm_provider_state where exhausted_until > now();`

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

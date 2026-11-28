# Hỏi thầy answered "ta mỏi rồi" right after real LLM keys went live

- Date: 2026-11-28
- Tags: prod, llm, config

## Symptom
After PR #57 sealed real `GEMINI_API_KEY` / `GROQ_API_KEY` (prod `LLM_PROVIDERS=gemini,groq`), a free question on https://xemtuvingay.com/vi/hoi returned the refusal frame "Hôm nay ta mỏi rồi, con để mai quay lại hỏi ta nhé" within ~20 s: every provider failed.

## Root cause
Both default model ids in `LlmConfiguration` were retired. With the prod keys, `POST {base}/chat/completions` returned 404 for `gemini-2.5-flash-lite` (Gemini OpenAI-compatible endpoint; the id is still listed by `/models`, but chat returns `NOT_FOUND`) and for `llama-3.3-70b-versatile` (no longer in Groq's `/models`). The adapter maps 404 to "unavailable", so the gateway fell through to "all providers exhausted". The ids were chosen at F1.4 time and never exercised: CI uses the fake provider and no real key existed until today.

## Fix
Defaults `GEMINI_MODEL=gemini-3.5-flash-lite` and `GROQ_MODEL=openai/gpt-oss-120b` (also `LLM_PAID_MODEL`), each probed with the prod keys: HTTP 200 and a Vietnamese persona answer within the FREE budget of 350 tokens (gpt-oss: 267 tokens, 134 of them reasoning).

## How to catch earlier
Before and right after enabling a real provider key, call `GET {base}/models` and one small chat completion with the configured model id, printing only the HTTP status. Hosted model ids are retired without notice; a default picked weeks earlier is no evidence it still works. The ERROR log `llm provider rejected request provider=<id> status=404` names this case.

## Escalation
none (first occurrence). If it recurs: a startup or scheduled probe that logs ERROR (and alerts) when a configured model returns 404.

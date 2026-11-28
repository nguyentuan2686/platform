# Diagnosing Hỏi thầy with the prod LLM keys used up prod's free quota

- Date: 2026-11-28
- Tags: prod, llm, process

## Symptom
After #66 (prompt v4) deployed, a free "Sự nghiệp" question on xemtuvingay.com was still refused. While replaying prompts, Gemini started answering HTTP 429 `RESOURCE_EXHAUSTED` ("You exceeded your current quota") without `Retry-After`, and Groq answered 429 after a few calls.

## Root cause
1. Process: the investigations for #63 and #66 replayed prompts against Gemini and Groq with the **prod** keys, about 100 Gemini calls in one afternoon. Free tiers share one quota per key, so those calls competed with real users and used up Gemini's daily quota. The gateway then skipped Gemini, and prod answered through Groq alone (a weaker fallback), which muddled the very test being diagnosed.
2. Code: a 429 without `Retry-After` marked a provider exhausted until UTC midnight. Google resets the Gemini free quota at midnight Pacific (08:00 UTC), so the retry at 00:00 UTC hits 429 again and Gemini is skipped for a whole extra day.
3. The decline log `qa model declined` named no provider, so without logs nobody could tell whether Gemini or Groq produced a refusal.

## Fix
`LlmGateway`: no `Retry-After` backs off one hour; an explicit `Retry-After` is honoured, capped at one day. `qa model declined ... provider=<id>`. Probing stopped. The refusal itself stays unexplained until a reading-tuvi log is read (v4 answered 30/30 Gemini and 7/7 Groq calls on 12 real charts).

## How to catch earlier
Never probe or eval with the prod keys. Use a separate dev key per provider (its own quota), keep a probe to a handful of calls, and send bodies from a UTF-8 file. Before diagnosing a prod answer, get the reading-tuvi log line for that reading (provider, reason) instead of replaying blind.

## Escalation
none (first occurrence). If it recurs: a `secrets.local`-free dev key file (`deploy/local/.env`) documented as the only key for local probes, and a runbook rule in `docs/runbooks/llm-gateway.md`.

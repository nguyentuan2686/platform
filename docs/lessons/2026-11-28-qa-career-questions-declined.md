# Hỏi thầy declined ordinary career questions once real LLMs answered

- Date: 2026-11-28
- Tags: prod, llm, prompt

## Symptom
Right after #63 (current Gemini/Groq model ids) deployed, two free questions with topic "Sự nghiệp" on https://xemtuvingay.com/vi/hoi got refusal frames instead of answers: "Sự nghiệp của con năm 2027 thế nào ạ?" and "Năm tới con có nên chuyển việc không ạ?".

## Root cause
Three causes, found by replaying the exact prompt (`QaService` with the real chart fixture and `knowledge/tuvi`) against both models with the prod keys:
1. Prompt v3 never said which year it is. "Năm tới" could not be tied to the `Năm 2027 lưu niên` fact, so Gemini answered `KHONG_DU_TU_LIEU` 6/6, and Groq did the same.
2. Rules 1 and 5 read as "decline when in doubt": "nếu các khối đó chưa đủ" and "ngoài chủ đề" made a timing or should-I question look out of scope or under-informed (`NGOAI_PHAM_VI` 1/6 on the 2027 question).
3. Groq `openai/gpt-oss-120b` at its default reasoning effort spent all 350 FREE tokens on reasoning (`reasoning_tokens: 348`, `finish_reason: length`, empty content), so the fallback never answered.
The question guard was ruled out with a test (both questions pass `QuestionGuard`). The free quota staying at 3 was misleading: the web refreshes the quota only after `done`/`queued`.

## Fix
Prompt v4 (`PROMPT_VERSION` 4, so cached v3 answers are not reused): a `NĂM NAY: <year>` line; rule 7 says "năm tới" / "sang năm" is the next year; rule 1 answers from a general tendency and declines only when nothing relates to the topic; rule 5 says the asker calls themselves "con" and that timing and should-I questions within the topic are in scope, while other people's charts and non-tử-vi questions stay out. Groq sends `reasoning_effort: low` (`GROQ_REASONING_EFFORT`). `Sentinel.detect` accepts misspelled tails (`KHONG_DU_TIEU_LIEU`). Replayed after the fix: both questions answered 6/6 (Gemini) and 3/3 (Groq); off-topic controls (gold price, a spouse's chart) still refused 6/6 by Gemini.

## How to catch earlier
A prompt change, or a new model, gets replayed against the real model with a handful of real questions per topic (in-scope timing and should-I questions plus off-topic controls), sending the request body from a UTF-8 file: Git Bash on Windows mangles Vietnamese passed as a curl argument, which itself produced bogus `NGOAI_PHAM_VI` answers during this investigation.

## Escalation
none (first occurrence). If a prompt regression recurs: a small eval set per topic (questions + expected answer/refusal) run against the real providers before a prompt or model change is merged (manual or nightly, never in PR CI).

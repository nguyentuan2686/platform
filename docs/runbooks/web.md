# Runbook: web (Next.js BFF, tử vi site)

Local test values live in `deploy/local/.env.example`; real values only via Sealed Secrets. Server-side secrets (`INTERNAL_API_TOKEN`, `ADMIN_API_TOKEN`, `SEPAY_WEBHOOK_SECRET`) never reach the browser; logs carry ids only, never birth data or emails.

## 1. Environment
| Var | Local (compose) | Prod |
|---|---|---|
| `WEB_ORIGIN` | `http://localhost:3001` | the public https origin, **also as the Docker build arg** (canonical URLs and the sitemap are baked at build) |
| `COOKIE_SECURE` | `0` | `1` (cookie becomes `__Host-tv_sid`, Secure) |
| `DEPLOY_ENV` | `local` | `prod` |
| `DEV_PAYMENT_SIMULATOR` | `1` (compose only) | **never set**; `DEPLOY_ENV=prod` also force-disables it |
| `ADMIN_ACCOUNT_IDS` | empty (nobody) | comma list of account UUIDs, env or Sealed Secret, never Git |
| `ADMIN_API_TOKEN` | test value | Sealed Secret; empty makes `/vi/quan-tri` 404 |
| `GOOGLE_LOGIN` | `0` | `1` once Google OAuth exists |
| `SEPAY_WEBHOOK_AUTH` / `SEPAY_WEBHOOK_SECRET` | test values | **not set on web in prod**: only the dev payment simulator uses them (force-disabled when `DEPLOY_ENV=prod`); the secret lives in payment-svc only |
| `QA_STREAM_TIMEOUT_MS` | `95000` | `95000` |

## 2. When Plan 4 deploys
1. Build the image with `--build-arg WEB_ORIGIN=https://<public domain>`; set the same `WEB_ORIGIN` at runtime (the CSRF Origin check compares against it).
2. The prod overlay sets `DEPLOY_ENV=prod`, `COOKIE_SECURE=1`, and no `DEV_PAYMENT_SIMULATOR`.
3. Google login: add `${WEB_ORIGIN}/vi/dang-nhap/google` to identity-svc `OIDC_REDIRECT_URIS`, set the client id and secret there, then `GOOGLE_LOGIN=1` on web.
4. Admin: put your account id in `ADMIN_ACCOUNT_IDS`, open `/vi/quan-tri` ("chưa khớp"; see `payments.md`).

## 3. Local full-stack run (weak machine)
Heavy: one run at a time, no lgtm, order-svc or notify-svc.
1. `docker compose -f deploy/local/docker-compose.yml --profile app up -d --build --no-deps postgres redpanda mailpit chart-svc reading-tuvi identity-svc payment-svc web` (an old `pgdata` without the `identity`/`payment` dbs needs one `down -v`).
2. `scripts/smoke-web.sh http://localhost:3001`.
3. Full flow: `docker run --rm --network host -v "$PWD/services/web:/w" -w /w mcr.microsoft.com/playwright:v1.65.1-noble sh -c "npm ci && npx playwright test -c playwright.stack.config.ts"`.
4. `docker stats --no-stream`, then `docker compose -f deploy/local/docker-compose.yml --profile app down -v`.

## 4. Owner to-dos (Tuấn), none blocks the merge
1. SePay account and webhook, PayPal account (see `payments.md`).
2. Google OAuth client.
3. An email service for real magic links (identity-svc `MAIL_*`).
4. Groq and Gemini keys (see `llm-gateway.md`).
5. Hosting and a domain (Plan 4).
6. An accountant, and a lawyer review of the privacy, terms and refund pages (seller information for the e-commerce notice).

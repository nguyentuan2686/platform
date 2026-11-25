# Plan F1.5b — web tử vi đầy đủ: tài khoản, nạp lượt, hỏi thầy, lịch sử, chia sẻ, SEO

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On the local stack a Vietnamese user can do everything F1 sells. They cast a chart as a guest, then save it to an account (email magic link, or Google when configured). They buy credits or a pass through a VietQR checkout, which is paid locally by a dev-only simulated transfer, and the balance updates live. They ask Lão Thiên Cơ a topic question and watch the answer stream in with the ink effect, then ask 2 follow-ups. They can reread history, see transactions and request a refund, share a public chart link with an image card, and read SEO knowledge pages. Tuấn gets a "chưa khớp" admin page.

**Architecture:** `services/web` stays the only thing the browser talks to (BFF). The session cookie (httpOnly) holds the identity-svc session token. Server components, server actions and route handlers call identity-svc, payment-svc, reading-tuvi and chart-svc server-side with `X-Internal-Token` (plus `X-Admin-Token` for the admin page). Service URLs and tokens never reach the browser. The F1.5a in-memory birth store is replaced by the caller's identity chart profile, which is encrypted at rest and one per guest or account. Q&A SSE from reading-tuvi is piped through a route handler unchanged. Two small backend additions make this possible:
- identity-svc public share links for the profile;
- a reading-tuvi internal endpoint that moves a guest's free-chat history to the account at login (spec §11).

**Tech Stack:** Node 22 (Docker only), Next.js 15.5 App Router, React 19, TypeScript strict, CSS (existing `theme.css`, no UI framework), `next/og` `ImageResponse` (bundled with Next) for the share card, Vitest + Testing Library, Playwright (CI with stub upstreams; one local full-stack run). Backend additions: Java 21 / Spring Boot as in `services/identity-svc` and `services/reading-tuvi` (Flyway, Testcontainers, ArchUnit).

**Spec:** `docs/superpowers/specs/2026-11-24-fortune-platform-design.md`:
- §2 G1–G3b, G6;
- §5.4, §5.7;
- §6.1 web;
- §6.2 (guest cookie, magic link + Google, encrypted profile);
- §7 privacy;
- §9 F1.5 scope;
- §11 (guest → account merge);
- §12.1 (Vietnamese UI → VN calendar, "đổi lịch" on the profile page);
- §13.2–13.4 (credits, 2 follow-ups, lock after first paid question, sharing rules);
- §14.1 (packs, prices, renewal reminder 3 days before), §14.2 (Lão Thiên Cơ: "ta"/"con"), §14.4 (streamed answer, ink effect, seal, reduced motion, "Hiện hết"), §14.5 (fonts);
- "Thanh toán SePay — cách cộng lượt" (QR, content = order code, "chưa khớp" queue, live update in seconds);
- §15 (disclaimer, 18+, refund policy page, transaction history, consent, AI label, no fear-selling);
- §16 (no external ads; house ads only).

Prototype: `docs/superpowers/specs/assets/2026-11-24-fortune-prototype-v2.html` (screens `home`, `chart`, `ask`, `topup`, `pay`, `reading`, `share`, `account`, `login`, the `tabbar`).

APIs:
- `contracts/openapi/identity-svc.yaml`
- `contracts/openapi/payment-svc.yaml`
- `contracts/openapi/reading-tuvi.yaml`
- `contracts/openapi/chart-svc.yaml`

Deferred-to-F1.5b lists: Notes of `docs/superpowers/plans/2026-11-25-plan-f1-3-identity-payment.md` and `2026-11-25-plan-f1-4-llm-qa.md`. Style and existing web: `docs/superpowers/plans/2026-11-25-plan-f1-5a-web-tuvi-preview.md`, `services/web/**`.

## Global Constraints

- Scope: site **tử vi**, language **vi**, routes under `/vi`. No astro, no en/zh, no PayPal button (PayPal is the international path, F3). Copy is Vietnamese in Lão Thiên Cơ's voice ("ta"/"con") wherever he speaks; neutral UI labels elsewhere.
- **BFF only:** the browser calls only same-origin pages, server actions and `/api/**` route handlers. Service base URLs, `INTERNAL_API_TOKEN`, `ADMIN_API_TOKEN`, `SEPAY_WEBHOOK_SECRET` and session tokens never appear in client bundles, HTML, JSON sent to the browser, URLs or logs. `npm run check:bundle` (Task 2) enforces the bundle part.
- **Session cookie:** the value is the identity session token. Name `__Host-tv_sid` when `COOKIE_SECURE=1`, else `tv_sid`. Attributes: `HttpOnly; SameSite=Lax; Path=/`, plus `Secure` when `COOKIE_SECURE=1`. `Max-Age` runs until the session's `expires_at` (guest 180 d, account 30 d). A guest session is created lazily, only when the user saves a chart, asks a free question or starts a login.
- **CSRF:**
  - Server actions rely on Next's built-in Origin/Host check (do not set `serverActions.allowedOrigins`).
  - Every state-changing route handler (`POST`/`DELETE` under `/api/**`) calls `assertSameOrigin(req)`: the `Origin` header must equal `WEB_ORIGIN`. When `Origin` is absent, `Sec-Fetch-Site` must be `same-origin`. Otherwise answer 403 `{"reason":"forbidden"}`.
  - No state change on GET, with one exception: the Google OIDC callback, which is protected by the state cookie.
- **Privacy (§7):**
  - Birth date, time, zone and gender never appear in URLs, logs or public pages. The public share page shows only gender, giờ (địa chi) and năm can chi; it shows no solar date and no lunar day/month.
  - Emails are shown only masked (`email_masked` from `/me`). The only log fields allowed are ids (`account_id`, `order_id`, `reading_id`), upstream name and status.
  - The magic-link verify page sends `Referrer-Policy: no-referrer` and loads no third-party resource.
- **Consent (§15):**
  - Saving a chart requires a checkbox: "Ta được lưu ngày giờ sinh của con (đã mã hóa) để lập lá số. Xem chính sách riêng tư." The link goes to `/vi/chinh-sach-rieng-tu`.
  - The first question requires a checkbox: "Con đồng ý gửi lá số ẩn danh và câu hỏi tới nhà cung cấp AI ở nước ngoài để luận giải." Accepting sets cookie `tv_ai_ok=1` (HttpOnly, Lax, 365 d).
  - Buying requires a checkbox: "Tôi đủ 18 tuổi". This sends `adult_confirmed=true`.
- **Disclaimer and labels:**
  - The footer disclaimer stays on every page.
  - Every Q&A answer shows the server `disclaimer` and the line "Nội dung do AI biên soạn từ kho tư liệu."
  - Prices are shown as `29.000đ` (dot thousands, no decimals).
  - Dates and times are shown in `Asia/Ho_Chi_Minh` as `dd/mm/yyyy` / `HH:mm`.
- **Products (§14.1, codes from payment-svc):**

  | Code | Price | Copy |
  |---|---|---|
  | `CREDIT_1` | 29.000đ | "1 lượt" · "Thử một câu hỏi" |
  | `CREDIT_5` | 119.000đ | "5 lượt" · "23.800đ mỗi lượt" |
  | `PASS_MONTH` | 49.000đ | "Thẻ tháng" · "30 ngày · 2 lượt hỏi · xem ngày chi tiết (sắp có)" |
  | `PASS_YEAR` | 399.000đ | "Thẻ năm" · "365 ngày · hỏi không giới hạn · bài vận năm (sắp có)" |

  The amounts are rendered from `/products`; they are never hard-coded.
- **Topics (contract enum → label · glyph · hint, from the prototype):**

  | Enum | Label | Glyph | Hint |
  |---|---|---|---|
  | `TinhDuyen` | Tình duyên | 緣 | Cung Phu Thê, đào hoa, thời điểm gặp gỡ |
  | `SuNghiep` | Sự nghiệp | 業 | Cung Quan Lộc, hướng đi hợp mệnh |
  | `TaiLoc` | Tài lộc | 財 | Cung Tài Bạch, cách giữ và sinh tiền |
  | `SucKhoe` | Sức khỏe | 康 | Cung Tật Ách, điều cần lưu ý |
  | `GiaDao` | Gia đạo | 家 | Cung Điền Trạch, cha mẹ, con cái |
  | `VanNam` | Vận năm | 運 | Lưu niên, tháng tốt xấu |
- **Look:** prototype v2, tử vi site only: lacquer/cinnabar/gold tokens in `theme.css`; Charm for display, Noto Serif for body, Noto Serif Display for labels; Ma Shan Zheng for glyphs. Bottom `tabbar` with Hôm nay → `/vi/la-so#hom-nay`, Lá số → `/vi/la-so`, Hỏi thầy → `/vi/hoi`, Tài khoản → `/vi/tai-khoan`.
- **Accessibility and layout:**
  - All animation respects `prefers-reduced-motion`.
  - No horizontal scroll at 375 px.
  - Every input has a `<label>`. Errors use `role="alert"`. Buttons are real `<button>` elements. Status changes such as payment received and an answer done are announced via `aria-live="polite"`.
- **Web env vars:**

  | Var | Default | Notes |
  |---|---|---|
  | `CHART_SVC_URL` | `http://localhost:8082` | existing |
  | `READING_URL` | `http://localhost:8083` | existing |
  | `IDENTITY_SVC_URL` | `http://localhost:8084` | |
  | `PAYMENT_SVC_URL` | `http://localhost:8085` | |
  | `INTERNAL_API_TOKEN` | (empty → header omitted) | |
  | `ADMIN_API_TOKEN` | (empty → admin page 404) | |
  | `ADMIN_ACCOUNT_IDS` | (empty) | comma list of account UUIDs |
  | `WEB_ORIGIN` | `http://localhost:3001` | |
  | `COOKIE_SECURE` | `0` | |
  | `GOOGLE_LOGIN` | `0` | `1` shows the Google button |
  | `DEV_PAYMENT_SIMULATOR` | `0` | |
  | `DEPLOY_ENV` | `local` | `prod` force-disables the simulator |
  | `SEPAY_WEBHOOK_AUTH` | `apikey` | |
  | `SEPAY_WEBHOOK_SECRET` | (empty) | |
  | `QA_STREAM_TIMEOUT_MS` | `95000` | |
  | `KNOWLEDGE_DIR` | `../../knowledge/tuvi` | build time |

  Compose sets the test values (`local-test-…`) and `DEV_PAYMENT_SIMULATOR=1`.
- **Upstream timeouts:** 5 s for JSON calls; `QA_STREAM_TIMEOUT_MS` for SSE. Down, 5xx or timeout → `UpstreamError` → themed 503 ("Lão Thiên Cơ cần một lát").
- **Platform:** `/health/live` and `/health/ready` unchanged; JSON log lines with the 6 mandatory fields (`src/lib/log.ts`). Gates: `npm run lint`, `npm run typecheck`, `npm test` (≥ 80% lines in `src/lib/**`), `npm run build`, `npm run check:bundle`, Playwright in CI.
- **Java additions** follow `.claude/skills/java-service-conventions/SKILL.md`: hexagonal layout, ArchUnit, ≥ 80% coverage on domain+application, problem+json, Flyway `V<n>__…`, OpenAPI contract updated with `ContractTest` green.
- **Weak machine (2 cores / 12 GB):** web `mem_limit: 256m`. Locally run Vitest only; Playwright in CI, except the single full-stack run in Task 8. Never the owner's email/phone; only `local-test-` secrets.

## Review Focus

1. **Two tabs / double click on a paid question.** The client creates one `reading_id` per submit, disables the button while streaming and reuses that id on a retry. Expected: one charge, and the second request replays or gets `reading_in_progress` ("Câu này ta đang luận, con chờ chút."). Tests in Task 5 (`QaAsk.test.tsx: double click sends one request`; `askRoute.test.ts: 409 reading_in_progress maps to reason`).
2. **Stream drops mid-answer** (network loss, tab closed, upstream timeout). The BFF aborts the upstream when the client disconnects. The client shows "Mất kết nối. Bài luận vẫn được lưu, con xem lại trong Lịch sử nhé." with a link to `/vi/hoi/<id>`. A paid reading is never asked again with a new id automatically. Tests in Task 5 (`parseSse` truncated chunk; `QaAsk` stream error → message + link; route handler passes `req.signal`).
3. **Payment arrives late or after the page closed.** The order expired (30 min), the transfer comes later, or the user reopens `/vi/thanh-toan/<id>` from history. Expected: the page shows the current status from payment-svc (PAID → success and balance). An EXPIRED page shows the late-payment note, not an error. Polling stops on non-PENDING and pauses while the tab is hidden. Tests in Task 4 (`OrderStatus.test.tsx`).
4. **Magic link opened by a mail scanner, in another browser, or twice.** GET only renders a button; verification happens on POST. Another browser logs in without a merge (no guest cookie). A second use shows "Link đã hết hạn hoặc đã dùng". `next` cannot redirect off-site. Tests in Task 3 (`loginActions.test.ts`, `safeNext.test.ts`).
5. **Secrets or birth data reaching the browser or a public page.** Expected:
   - no token or service URL in `.next/static` or in HTML;
   - `document.cookie` has no session cookie;
   - the share page has no solar date and no lunar day/month;
   - the result and share URLs carry no birth digits.

   Tests: `check:bundle` (Task 2), e2e `session cookie is httpOnly` (Task 2), `SharePage.test.tsx` and e2e (Task 6).

---

### Task 1: Backend additions — profile share links (identity-svc) and guest Q&A merge (reading-tuvi)

**Files:**
- Create: `services/identity-svc/src/main/resources/db/migration/V4__profile_share.sql`.
- Create under `services/identity-svc/src/main/java/dev/platform/identity/`:
  - `application/{ShareUseCase.java, ShareRepositoryPort.java}`
  - `domain/ShareId.java`
  - `adapter/in/web/{ShareController.java, ShareResponse.java, SharedProfileResponse.java}`
  - `adapter/out/persistence/JdbcShareRepository.java`
- Tests: `domain/ShareIdTest`, `application/ShareUseCaseTest`, `ShareIT` (Testcontainers).
- Modify: `contracts/openapi/identity-svc.yaml` (version `0.3.0`).
- Create under `services/reading-tuvi/src/main/java/dev/platform/reading/tuvi/`: `application/qa/QaMergeUseCase.java`, `adapter/in/web/QaMergeController.java`, `adapter/in/web/MergeRequest.java`. Test `QaMergeIT`.
- Modify: `QaRepositoryPort` + its JDBC adapter (`moveGuestReadings`); `contracts/openapi/reading-tuvi.yaml`.

**Interfaces:**
- Produces V4: `profile_share(id text PRIMARY KEY, profile_id uuid NOT NULL REFERENCES chart_profile(id) ON DELETE CASCADE, created_at timestamptz NOT NULL DEFAULT now(), revoked_at timestamptz NULL)` + `CREATE UNIQUE INDEX profile_share_active ON profile_share(profile_id) WHERE revoked_at IS NULL`.
- Produces `ShareId.random(SecureRandom) -> ShareId`: 16 random bytes, base64url without padding (22 chars). `ShareId.parse(String)` rejects anything else.
- Produces identity endpoints (guest or account bearer + internal token):
  - `PUT /me/profile/share` → 200 `{share_id, created_at}`. Idempotent: returns the active one. 409 `reason=no_profile` without a profile.
  - `GET /me/profile/share` → 200 the same body, or 404.
  - `DELETE /me/profile/share` → 204 (revokes; no-op when none).
- Produces identity endpoint (internal token only, no bearer): `GET /shares/{share_id}` → 200 `{birth: {birth, zone, gender}, calendar}`. 404 for unknown, malformed, revoked, or a deleted profile. The response record overrides `toString` → `…[redacted]`.
- Produces reading-tuvi endpoint (internal token): `POST /internal/qa/merge` body `{guest_id: uuid, account_id: uuid}` → 200 `{moved: int}`. It moves every `qa_reading` with `subject_kind='guest' AND subject_id=:guest` to `('account', :account)`. Idempotent: a repeat moves 0. 400 on malformed ids.

- [ ] **Step 1: Failing tests (identity).**
  - `ShareIdTest`: length 22, url-safe alphabet, `parse("bad")` throws.
  - `ShareUseCaseTest`, with fakes: `create_is_idempotent_returns_active`; `create_without_profile_conflicts_no_profile`; `revoke_then_get_is_empty`; `create_after_revoke_issues_new_id`.
  - `ShareIT`:
    - guest PUT → GET `/shares/{id}` returns the decrypted birth;
    - DELETE → `/shares/{id}` 404;
    - deleting the profile → 404 (cascade);
    - guest merged into an account without a profile → share still resolves (profile moved, same id);
    - `/shares/{id}` without the internal token → 401;
    - captured stdout contains no birth string.
- [ ] **Step 2: Failing test (reading-tuvi).** `QaMergeIT`:
  - Two FREE readings as guest G (via the existing fake identity stub). Merge `{G, A}` → `{moved: 2}`, and `GET /tuvi/qa/readings` as A lists both.
  - A second merge → `{moved: 0}`.
  - Without the internal token → 401.
  - `guest_id` "x" → 400 validation-error.
- [ ] **Step 3: Run** `mvn -q -pl services/identity-svc,services/reading-tuvi -am verify` → FAIL on the new tests.
- [ ] **Step 4: Implement** both, then update the OpenAPI files (paths, schemas `ShareLink`, `SharedProfile`, `MergeRequest`, `MergeResult`). `ContractTest` must pass in both services.
- [ ] **Step 5: Run** the same command → PASS; `mvn spotless:apply`.
- [ ] **Step 6: Commit** `feat(identity,reading): profile share links and guest Q&A merge`.

---

### Task 2: BFF core — config, service clients, session cookie, CSRF guard; chart saved to the profile

**Files:**
- Create in `services/web/src/lib/`: `config.ts`, `http.ts`, `identity.ts`, `payment.ts`, `qa.ts`, `session.ts`, `sameOrigin.ts`, `format.ts`.
- Tests: `config.test.ts`, `http.test.ts`, `session.test.ts`, `sameOrigin.test.ts`, `format.test.ts`, `identity.test.ts`.
- Modify: `src/lib/upstream.ts` (built on `http.ts`), `src/lib/types.ts` (`calendar: "VN" | "CN"`), `src/lib/birthForm.ts` (+ `consent`), `src/app/vi/actions.ts`, `src/components/BirthForm.tsx`, `src/components/{SiteHeader,SiteFooter}.tsx`, `src/app/vi/page.tsx`.
- Create: `src/app/vi/la-so/page.tsx`, `src/components/{TabBar.tsx, BalanceChip.tsx}`.
- Create: `src/app/vi/{chinh-sach-rieng-tu,dieu-khoan,hoan-tien}/page.tsx`.
- Create: `scripts/check-client-bundle.mjs` (in `services/web`).
- Delete: `src/lib/birthStore.ts` (+ test), `src/app/vi/la-so/[id]/**`.
- Modify: `e2e/stub-upstream.mjs` → split into `e2e/stub/{server.mjs, chart.mjs, reading.mjs, identity.mjs}`, with in-memory state and a `POST /__reset` control. Modify `e2e/reading.spec.ts` (new URL `/vi/la-so`).
- Modify: `playwright.config.ts` (stub ports 18082–18085; env for the new URLs), `package.json` (`"check:bundle": "node scripts/check-client-bundle.mjs"`), `.github/workflows/_node-service.yml` (run `npm run check:bundle` after build), `deploy/local/docker-compose.yml` (web env + `depends_on` identity-svc).

**Interfaces:**
- `config.ts`: `cfg()` returns a frozen object read from `process.env` at call time with the defaults in Global Constraints. It also exposes `cfg().simulatorEnabled` (`DEV_PAYMENT_SIMULATOR==="1" && DEPLOY_ENV!=="prod"`) and `cfg().adminIds: Set<string>`.
- `http.ts`: `callJson<T>(svc: "chart"|"reading"|"identity"|"payment", path: string, opts?: { method?: "GET"|"POST"|"PUT"|"DELETE"; bearer?: string; body?: unknown; admin?: boolean; timeoutMs?: number }) -> Promise<T>`. It adds `X-Internal-Token` when configured, `Authorization: Bearer` when `bearer` is set, and `X-Admin-Token` when `admin`. Errors:
  - `UpstreamError` for network errors, timeouts, 5xx and bad JSON;
  - `UpstreamValidation(fields)` for 400;
  - `UpstreamProblem(status: 401|403|404|409, reason?: string)` for those statuses.

  204 → `undefined`. It logs only `{upstream, status}`; never the path's query values, the body or headers.
- `identity.ts`:
  - `createGuest()`, `me(bearer)`, `getProfile(bearer)`, `putProfile(bearer, {label, birth, calendar})`, `deleteProfile(bearer)`;
  - `entitlements(bearer)`, `ledger(bearer)`;
  - `requestMagicLink(email, guestBearer?)`, `verifyMagicLink(token, guestBearer?)`;
  - `googleAuthorization(redirectUri, guestBearer?)`, `googleCallback(code, state, guestBearer?)`;
  - `logout(bearer)`;
  - `getShare(bearer)`, `createShare(bearer)`, `deleteShare(bearer)`, `resolveShare(shareId)`.

  Types mirror the contract (`Me`, `Profile`, `Entitlements`, `LedgerEntry`, `LoginSession`, `ShareLink`, `SharedProfile`).
- `payment.ts`:
  - `listProducts()` (site `tuvi`, `VND`), `createOrder(accountId, productCode)` (`SEPAY`, `adult_confirmed: true`), `getOrder(id, accountId)`, `listOrders(accountId)`, `requestRefund(orderId, accountId)`;
  - admin: `listUnmatched()`, `assignUnmatched(id, orderCode)`, `resolveUnmatched(id, resolution, note?)`, `listRefundRequests()`, `completeRefundRequest(id)`;
  - `postSepayWebhook(body: object)`.

  Only `payment.ts` is written now (types + functions, unit-tested with mocked fetch); later tasks wire it to pages.
- `qa.ts`:
  - `askStream(bearer, body, signal) -> Promise<Response>` and `followUpStream(bearer, id, body, signal) -> Promise<Response>`: raw upstream responses. They throw `UpstreamProblem`/`UpstreamValidation` on non-200.
  - `listReadings(bearer)`, `getReading(bearer, id)`, `deleteReadings(bearer)`, `freeQuota(bearer)`, `mergeGuest(guestId, accountId)`.
- `session.ts`:
  - `sessionCookieName()`, `readSessionToken() -> Promise<string|undefined>`, `setSessionCookie(token, expiresAt: string)`, `clearSessionCookie()`;
  - `ensureSession() -> Promise<string>`: the existing cookie, else `createGuest()` + set the cookie. Only call it from actions or route handlers.
  - `getViewer()` = React `cache`d, returns `Promise<Viewer>`:
    ```ts
    type Viewer =
      | { kind: "anon" }
      | { kind: "guest"; guestId: string; token: string }
      | { kind: "account"; accountId: string; emailMasked: string; token: string; entitlements: Entitlements; isAdmin: boolean };
    ```
    A 401 from `/me` → `anon`.
- `sameOrigin.ts`: `assertSameOrigin(req: Request): void` throws `ForbiddenOrigin`. Also `forbidden() -> Response` (403 JSON `{reason:"forbidden"}`).
- `format.ts`: `formatVnd("119000.00") -> "119.000đ"`, `formatDateVn(iso) -> "dd/mm/yyyy"`, `formatDateTimeVn(iso) -> "HH:mm dd/mm/yyyy"`. All are in `Asia/Ho_Chi_Minh`.
- `birthForm.ts`: `FormErrors` gains `consent`. Missing consent → "Con cần đồng ý để ta lưu ngày giờ sinh."

- [ ] **Step 1: Failing tests.**
  - `config`: defaults; `simulatorEnabled` is false when `DEPLOY_ENV=prod` even with the flag at 1.
  - `http`:
    - internal token header present only when configured;
    - 400 → `UpstreamValidation`;
    - 409 `{reason:"no_credits"}` → `UpstreamProblem(409,"no_credits")`;
    - timeout → `UpstreamError`;
    - captured stdout has no bearer and no body.
  - `session`:
    - cookie name `__Host-tv_sid` + `Secure` when `COOKIE_SECURE=1`; `tv_sid` without `Secure` otherwise;
    - attributes include `HttpOnly`, `SameSite=Lax`, `Path=/`;
    - `getViewer` maps `/me` guest/account/401;
    - `isAdmin` is true only for ids in `ADMIN_ACCOUNT_IDS`.

    Mock `next/headers` `cookies()`.
  - `sameOrigin`:
    - Origin equal → ok;
    - other Origin → throws;
    - no Origin + `Sec-Fetch-Site: same-origin` → ok;
    - no Origin + `cross-site` → throws;
    - neither → throws.
  - `format`: `formatVnd("29000.00")=="29.000đ"`; an ISO `2026-11-24T17:30:00Z` → `25/11/2026`.
  - `actions.test.ts` (updated):
    - valid form + consent → `ensureSession`, `putProfile` with `calendar:"VN"` when there is no profile (the existing profile's calendar otherwise), redirect `/vi/la-so`;
    - missing consent → `consent` error;
    - `UpstreamProblem(409,"profile_locked")` → `date` error "Lá số của con đã khóa sau lượt hỏi đầu tiên, không đổi ngày giờ sinh được nữa."
- [ ] **Step 2: Run** `docker run --rm -v "$PWD/services/web:/app" -w /app node:22-alpine sh -c "npm ci && npm test"` → FAIL.
- [ ] **Step 3: Implement** the libs, then the flow and pages:
  - `/vi/la-so`: viewer without a profile → `redirect("/vi")`. Otherwise it renders the F1.5a page body (Board, TodayPanel with `id="hom-nay"`, Dialogue) from the profile's birth.
  - `/vi`: when a profile exists, a "Xem lá số của con" link appears above the form.
  - `TabBar` in the root layout: 4 items, `aria-current` on the active one; items whose page does not exist yet are omitted until their task adds them. This task ships Hôm nay and Lá số.
  - `BalanceChip` in `SiteHeader`:
    - account → "Còn N lượt", or "Thẻ tháng"/"Thẻ năm" when a pass is active;
    - guest/anon → "Đăng nhập" (rendered from Task 3 on).
  - Footer links to the three legal pages.
  - Legal pages, static, `robots: index`:
    - privacy: the data collected, AES encryption, 90-day free-chat retention, anonymised cross-border LLM transfer, deletion rights;
    - terms: cultural/entertainment positioning, no "giải hạn/cúng/bùa", 18+ to buy;
    - refund: a failed reading → credit returned automatically after 24 h; unused credit packs → money back on request; passes → handled by hand; how to ask, from the transactions page.
  - `check-client-bundle.mjs`: scans `.next/static/**` and fails (exit 1, prints the file) on any of: `X-Internal-Token`, `X-Admin-Token`, `local-test-`, `:8082`, `:8083`, `:8084`, `:8085`, `IDENTITY_SVC_URL`, `PAYMENT_SVC_URL`.
  - Compose web env adds `IDENTITY_SVC_URL=http://identity-svc:8084`, `PAYMENT_SVC_URL=http://payment-svc:8085`, `INTERNAL_API_TOKEN=${INTERNAL_API_TOKEN:-local-test-internal-token}`, `WEB_ORIGIN=http://localhost:3001`, `COOKIE_SECURE=0`.
- [ ] **Step 4: E2E** (`e2e/reading.spec.ts`, stub identity keeps profiles per bearer):
  - submit with consent → URL is exactly `/vi/la-so` with no birth digits;
  - 12 palaces;
  - Hiện hết → seal;
  - `session cookie is httpOnly`: `page.evaluate(() => document.cookie)` does not contain `tv_sid`, while `context.cookies()` has it with `httpOnly: true`;
  - 375 px with no horizontal scroll on `/vi`, `/vi/la-so` and `/vi/chinh-sach-rieng-tu`;
  - the upstream-down test still passes.
- [ ] **Step 5: Gates** `docker run --rm -v "$PWD/services/web:/app" -w /app node:22-alpine sh -c "npm ci && npm run lint && npm run typecheck && npm test && npm run build && npm run check:bundle"` → all green, `check:bundle` prints `OK`.
- [ ] **Step 6: Commit** `feat(web): BFF session, service clients and profile-backed chart`.

---

### Task 3: Login (magic link, Google), logout, guest merge, account page

**Files:**
- Create: `src/app/vi/dang-nhap/{page.tsx, actions.ts}`, `src/app/vi/dang-nhap/xac-nhan/page.tsx`, `src/app/vi/dang-nhap/google/route.ts`, `src/app/api/auth/google/route.ts`.
- Create: `src/app/vi/tai-khoan/{page.tsx, actions.ts}`, `src/lib/safeNext.ts`, `src/components/{LoginForm.tsx, ConfirmLogin.tsx, ProfileCard.tsx}`.
- Tests: `src/lib/safeNext.test.ts`, `src/app/vi/dang-nhap/loginActions.test.ts`, `src/app/api/auth/google/google.test.ts`, `src/app/vi/tai-khoan/accountActions.test.ts`, `src/components/ProfileCard.test.tsx`, `e2e/login.spec.ts`.
- Modify: `e2e/stub/identity.mjs` (magic links: the issued token is exposed at `GET /__last-magic-token`), `e2e/stub/reading.mjs` (`POST /internal/qa/merge` recorded, readable at `GET /__calls`), `TabBar` (+ Tài khoản), `BalanceChip` ("Đăng nhập" → `/vi/dang-nhap`).

**Interfaces:**
- Consumes: `identity.*`, `qa.mergeGuest`, `session.*`, `cfg()`, `assertSameOrigin` (Task 2).
- `safeNext(raw: string | null | undefined) -> string`: returns `raw` only if it matches `^/vi(/[a-z0-9-]+)*/?$`, else `"/vi/la-so"`. That rejects `//evil`, `/vi/../x`, `https:`, backslashes and query strings.
- Cookie `tv_next` (HttpOnly, Lax, 15 min) stores `safeNext(searchParams.next)` when the login page is opened.
- Cookie `tv_oidc` (HttpOnly, Lax, 10 min, `Path=/vi/dang-nhap/google`) stores the OIDC `state`.
- `requestLogin(prev, fd) -> {sent: true} | {errors: {email}}`:
  1. `ensureSession()` gives the guest bearer.
  2. `requestMagicLink(email, guestBearer)`.
  3. Always `{sent:true}` on 202; the page then shows "Nếu email hợp lệ, ta đã gửi link đăng nhập. Link có hiệu lực 15 phút."
  4. 400 → "Email chưa đúng."; 503 → "Chưa gửi được thư, con thử lại sau ít phút."
- `confirmLogin(prev, fd)` (hidden `token`):
  1. Read the current viewer; if it is a guest, keep `guestId` and the bearer.
  2. `verifyMagicLink(token, guestBearer)`.
  3. `setSessionCookie(session_token, expires_at)`.
  4. If there was a guest → `mergeGuest(guestId, account_id)`. On failure, log `qa merge failed` with the ids only and continue.
  5. Clear `tv_next` and `redirect(safeNext(tv_next))`.

  401 → "Link đã hết hạn hoặc đã dùng. Con xin link mới nhé." The xác-nhận page renders only the button; nothing happens on GET. `export const metadata = { referrer: "no-referrer", robots: { index: false } }`.
- `POST /api/auth/google`: `assertSameOrigin`, then 404 unless `GOOGLE_LOGIN=1`. It calls `ensureSession()` and `googleAuthorization(WEB_ORIGIN + "/vi/dang-nhap/google", guestBearer)`, sets `tv_oidc`, and answers 303 to `authorization_url`.
- `GET /vi/dang-nhap/google?code&state`: 404 unless enabled. If `state` ≠ the `tv_oidc` cookie → 303 `/vi/dang-nhap?loi=google`. Otherwise `googleCallback` → cookie + merge + 303 `safeNext`. Upstream 401/503 → 303 `/vi/dang-nhap?loi=google` ("Đăng nhập Google chưa thành, con thử lại hoặc dùng email.").
- `logoutAction()`: `logout(bearer)` (ignore 401), clear the session cookie, `redirect("/vi")`.
- `/vi/tai-khoan`:
  - anon/guest: the prototype `login` copy ("Lưu lá số và nạp lượt", "Tạo tài khoản để giữ lá số, lịch sử bài luận và số lượt. Tặng 1 lượt cho lần đầu.") + a link to `/vi/dang-nhap?next=/vi/tai-khoan`.
  - account:
    - `ProfileCard`: label, giới tính, giờ, năm can chi (from chart-svc), lịch (Việt/Trung Hoa); the lock line "Mỗi tài khoản một lá số. Ngày giờ sinh đã khóa sau lượt hỏi đầu tiên." when `locked`; a "Sửa thông tin" link to `/vi` only when unlocked.
    - "Đổi lịch" form: `changeCalendar` → `putProfile` with the same birth and the other calendar.
    - "Xóa lá số" behind a `<dialog>` confirm: `deleteProfile` → redirect `/vi`.
    - Balance row ("Số lượt còn lại · Lượt không hết hạn").
    - Masked email.
    - "Đăng xuất".

- [ ] **Step 1: Failing tests.**
  - `safeNext`: accepts `/vi/mua` and `/vi/hoi`. Rejects `//evil.com`, `https://x`, `/vi/../admin`, `/vi\\x`, `/vi/mua?x=1`, `""`, `null` (all → `/vi/la-so`).
  - `loginActions`:
    - request always `{sent:true}` for a 202 whatever the email;
    - confirm with a guest calls `mergeGuest(guestId, accountId)` once and sets the cookie;
    - confirm without a session does not call merge;
    - merge failure still logs in;
    - 401 → the expired message;
    - redirects to the stored `tv_next`.
  - `google.test`:
    - disabled → 404;
    - wrong Origin → 403;
    - start sets `tv_oidc` and answers 303 to the upstream URL;
    - callback state mismatch → redirect `?loi=google` with no upstream call.
  - `accountActions`: `changeCalendar` flips VN↔CN keeping the birth; `deleteProfile` → redirect.
  - `ProfileCard`: the locked line is shown and "Sửa thông tin" hidden when `locked`.
- [ ] **Step 2: Run** `npm test` in Docker → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: E2E** `login.spec.ts`:
  1. Cast a chart as a guest, open `/vi/dang-nhap?next=/vi/tai-khoan`, submit an email → sent message.
  2. Fetch the token from the stub, open `/vi/dang-nhap/xac-nhan?token=…`, and assert that the stub recorded no verify on GET.
  3. Click "Đăng nhập" → lands on `/vi/tai-khoan` showing the masked email and "Còn 1 lượt"; the stub recorded `POST /internal/qa/merge`.
  4. Reuse the same link → expired message.
  5. Google button absent; logout → chip shows "Đăng nhập".
- [ ] **Step 5: Gates** (same command as Task 2) → green.
- [ ] **Step 6: Commit** `feat(web): magic-link and Google login, guest merge, account page`.

---

### Task 4: Buy credits/passes — VietQR checkout with live status, dev transfer simulator, transactions and refunds, renewal banner, "chưa khớp" admin

**Files:**
- Create: `src/app/vi/mua/{page.tsx, actions.ts}`, `src/app/vi/thanh-toan/[orderId]/page.tsx`, `src/app/api/orders/[id]/route.ts`, `src/app/api/dev/sepay/route.ts`.
- Create: `src/app/vi/tai-khoan/giao-dich/{page.tsx, actions.ts}`, `src/app/vi/quan-tri/{page.tsx, actions.ts}`.
- Create: `src/lib/{paymentCache.ts, products.ts, renewal.ts, sepaySim.ts}`, `src/components/{PackPicker.tsx, VietQrPanel.tsx, OrderStatus.tsx, PassBanner.tsx, CopyButton.tsx}`.
- Tests: `src/lib/{paymentCache,products,renewal,sepaySim}.test.ts`, `src/components/OrderStatus.test.tsx`, `src/app/api/dev/sepay/sepay.test.ts`, `src/app/api/orders/orders.test.ts`, `src/app/vi/mua/buyActions.test.ts`, `src/app/vi/quan-tri/admin.test.ts`, `e2e/purchase.spec.ts`.
- Modify: `src/app/vi/la-so/page.tsx` (house ad under the Hôm nay panel, §16: "Hỏi sâu hơn mỗi ngày với thẻ năm, hỏi không giới hạn." → `/vi/mua`, hidden when the viewer has a pass), `e2e/stub/payment.mjs` (new; a webhook marks the order PAID and bumps the stub identity balance), `deploy/local/docker-compose.yml` (web env `ADMIN_API_TOKEN`, `ADMIN_ACCOUNT_IDS=${ADMIN_ACCOUNT_IDS:-}`, `DEV_PAYMENT_SIMULATOR=1`, `DEPLOY_ENV=local`, `SEPAY_WEBHOOK_AUTH`, `SEPAY_WEBHOOK_SECRET` with the same defaults as payment-svc; `depends_on` payment-svc), `deploy/local/.env.example`, root layout (`PassBanner`).

**Interfaces:**
- Consumes: `payment.*`, `identity.entitlements/ledger`, `getViewer`, `assertSameOrigin`, `formatVnd`, `formatDateVn` (Task 2).
- `products.ts`: `productCopy(code) -> {title, hint}` (table in Global Constraints), `statusLabel(OrderStatus)`:

  | Status | Label |
  |---|---|
  | `PENDING` | Chờ thanh toán |
  | `EXPIRED` | Quá hạn |
  | `PAID` | Đã thanh toán |
  | `REFUND_REQUESTED` | Đang hoàn tiền |
  | `REFUNDED` | Đã hoàn tiền |

  Also `ledgerReasonLabel`:

  | Reason | Label |
  |---|---|
  | `signup_bonus` | Tặng khi tạo tài khoản |
  | `purchase` | Nạp lượt |
  | `consume` | Dùng lượt |
  | `refund` | Hoàn lượt |
  | `reversal` | Thu hồi khi hoàn tiền |

  `isRefundable(order) = status==="PAID" && product_code.startsWith("CREDIT_")`.
- `paymentCache`: `put(orderId, VietQrPayment)` / `get(orderId)`. In-process LRU, max 1 000, TTL 35 min (payment instructions are only returned by `createOrder`).
- `buyAction(prev, fd)`:
  - account required (guest/anon → `redirect("/vi/dang-nhap?next=/vi/mua")`);
  - `adult` checkbox required ("Con cần xác nhận đã đủ 18 tuổi.");
  - `product` ∈ codes;
  - `createOrder` → `paymentCache.put` → `redirect("/vi/thanh-toan/" + order_id)`;
  - 503 → "Kênh chuyển khoản chưa sẵn sàng, con thử lại sau."
- `/vi/thanh-toan/[orderId]`: `getOrder(id, accountId)` (404 → `notFound()`).
  - `VietQrPanel` from the cache: QR `<img alt="Mã VietQR">`, bank, account number, account name, amount, content (each with a `CopyButton`), "Giữ nguyên nội dung chuyển khoản. Lượt được cộng ngay khi ngân hàng báo về, thường dưới 1 phút." On a cache miss it shows only the code and amount + "Tạo đơn mới" → `/vi/mua`.
  - When `cfg().simulatorEnabled`: a button "Giả lập chuyển khoản (môi trường thử)".
- `GET /api/orders/[id]`: account session (401 `{reason:"unauthenticated"}`), → `{status, paid_at?}`; 404 passthrough.
- `OrderStatus` (client):
  - polls `/api/orders/{id}` every 3 s while `PENDING`;
  - pauses on `document.visibilityState==="hidden"`;
  - stops on any other status;
  - on `PAID` → `aria-live` "Đã nhận tiền. Lượt của con đã được cộng." + `router.refresh()` (header balance) + a link "Hỏi thầy ngay";
  - on `EXPIRED` → "Đơn đã quá 30 phút. Nếu con đã chuyển đúng nội dung, lượt vẫn được cộng khi ngân hàng báo về; quá 24 giờ chưa thấy, ta sẽ đối soát thủ công." It keeps polling every 30 s for 24 h after expiry, because late transfers still match.
- `sepaySim.ts`: `buildSepayWebhook({code, amount, now}) -> {headers, body}`. The body is `{id: <random int>, gateway: "TEST BANK", transactionDate: "yyyy-MM-dd HH:mm:ss" (Asia/Ho_Chi_Minh), accountNumber: "0000000000", content: code + " CHUYEN KHOAN", transferType: "in", transferAmount: <amount as number>, referenceCode: "SIM-" + id}`. Headers:
  - `apikey` → `Authorization: Apikey <SEPAY_WEBHOOK_SECRET>`;
  - `hmac-sha256` → `X-SePay-Signature: hex(HMAC_SHA256(secret, rawBody))`.
- `POST /api/dev/sepay` `{order_id}`:
  1. 404 when `!cfg().simulatorEnabled`.
  2. `assertSameOrigin`.
  3. Account session required.
  4. The order must belong to the viewer and be PENDING/EXPIRED.
  5. → `postSepayWebhook`, then 204.
- `renewal.ts`: `renewalNotice(pass: Entitlements["pass"], now: Date) -> {kind, daysLeft, endsAt} | null`. Non-null when `0 < ends_at − now ≤ 3 days`; `daysLeft` is in Vietnam calendar days, minimum 1. `PassBanner` text: "Thẻ tháng của con còn {n} ngày (đến {dd/mm/yyyy}). Gia hạn" → `/vi/mua`.
- `/vi/tai-khoan/giao-dich`:
  - orders table: code, product, amount, status, date;
  - ledger list: date, ±delta, reason label, topic label;
  - "Yêu cầu hoàn tiền" `<dialog>` confirm per refundable order → `refundAction`:
    - 201/200 → "Ta đã ghi nhận. Tiền sẽ được chuyển lại trong vài ngày làm việc."
    - 409 `credits_used` → "Lượt của gói này đã được dùng, không hoàn tiền được."
    - 409 `not_refundable` → "Đơn này không hoàn tiền tự động được."
- `/vi/quan-tri`:
  - `notFound()` unless `viewer.isAdmin && ADMIN_API_TOKEN`; `robots: noindex`.
  - Section "Chưa khớp": `listUnmatched` rows with id, amount, time (VN), reason label (NO_CODE "Thiếu mã", UNKNOWN_CODE "Mã lạ", AMBIGUOUS "Nhiều mã", ALREADY_PAID "Đơn đã trả", AMOUNT_LOW "Thiếu tiền"), candidate codes and content.
    - Form "Gán đơn" (`order_code`) → `assignUnmatched`. Errors: 404 → "Không thấy đơn"; 409 → `reason` label.
    - Form "Đóng" (`REFUNDED_MANUALLY` | `IGNORED` + note) → `resolveUnmatched`.
  - Section "Yêu cầu hoàn tiền": OPEN list → "Đã chuyển trả" → `completeRefundRequest`.
  - Every admin action re-checks `isAdmin` first.

- [ ] **Step 1: Failing tests.**
  - `products`: labels; `isRefundable` is true only for PAID `CREDIT_*`.
  - `paymentCache`: TTL expiry (fake timers) and LRU.
  - `renewal`:
    - ends in 2 d 5 h → `daysLeft 3`;
    - ends in 4 d → null;
    - already ended → null;
    - YEAR pass → kind YEAR;
    - null pass → null.
  - `sepaySim`:
    - apikey header exact;
    - the HMAC of a fixed body/secret equals a precomputed hex;
    - `transactionDate` for `2026-11-24T17:30:00Z` is `2026-11-25 00:30:00`.
  - `sepay.test`:
    - 404 when the flag is off;
    - 404 when the flag is 1 but `DEPLOY_ENV=prod`;
    - 403 on a foreign Origin;
    - 404 for another account's order;
    - 204 posts exactly one webhook.
  - `orders.test`: 401 without a session; returns `{status}` only (no payment details).
  - `buyActions`:
    - guest → redirect to login with `next=/vi/mua`;
    - missing adult → error;
    - success caches the payment and redirects.
  - `OrderStatus`, with fake timers and mocked fetch:
    - PENDING→PAID stops polling and calls `router.refresh` once;
    - hidden tab → no fetch;
    - EXPIRED → the late-payment note.
  - `admin`:
    - non-admin → `notFound`;
    - an admin assign posts `X-Admin-Token` (asserted on mocked fetch), and the token never appears in the returned state;
    - 409 `amount_low` → "Thiếu tiền".
- [ ] **Step 2: Run** `npm test` → FAIL.
- [ ] **Step 3: Implement.** Pack picker per prototype `topup`: radio cards, CREDIT_5 preselected, the "Thanh toán {price}" button text follows the selection. Add links to `/vi/hoan-tien` and `/vi/dieu-khoan` beside the 18+ checkbox. Add the account page links "Giao dịch" and "Nạp lượt".
- [ ] **Step 4: E2E** `purchase.spec.ts`:
  1. Logged in (helper from Task 3): `/vi/mua`, choose 1 lượt, tick 18+, pay.
  2. The QR panel shows the code; click the simulator → within 10 s "Đã nhận tiền" and the chip "Còn 2 lượt".
  3. `/vi/tai-khoan/giao-dich` lists the order as "Đã thanh toán"; refund → acknowledged.
  4. An anon visit to `/vi/mua` lands on `/vi/dang-nhap`.
  5. A non-admin visit to `/vi/quan-tri` → 404.
  6. 375 px with no horizontal scroll on `/vi/mua` and `/vi/thanh-toan/<id>`.
- [ ] **Step 5: Gates** → green.
- [ ] **Step 6: Commit** `feat(web): VietQR checkout, live status, transactions, refunds and admin queue`.

---

### Task 5: Hỏi thầy — topic Q&A with streamed ink answer, follow-ups, history

**Files:**
- Create: `src/app/vi/hoi/page.tsx`, `src/app/vi/hoi/[readingId]/page.tsx`, `src/app/vi/lich-su/{page.tsx, actions.ts}`.
- Create: `src/app/api/qa/readings/route.ts`, `src/app/api/qa/readings/[id]/route.ts`, `src/app/api/qa/readings/[id]/follow-ups/route.ts`.
- Create: `src/lib/{sse.ts, qaText.ts, topics.ts}`, `src/components/{TopicGrid.tsx, QaAsk.tsx, InkStream.tsx, FollowUpBox.tsx, ReadingStatusPoller.tsx, QaMessages.tsx}`.
- Tests: `src/lib/{sse,qaText,topics}.test.ts`, `src/app/api/qa/askRoute.test.ts`, `src/components/{QaAsk,InkStream,FollowUpBox}.test.tsx`, `e2e/qa.spec.ts`.
- Modify: `e2e/stub/reading.mjs` (SSE for asks/follow-ups, scripted by question text: contains "lỗi" → `queued`, "bệnh" → `refused`, otherwise 3 `delta` + `done`; also history list/get/delete, free quota), `TabBar` (+ Hỏi thầy), account page (link "Bài đã hỏi" → `/vi/lich-su`).

**Interfaces:**
- Consumes: `qa.*`, `identity.entitlements`, `getViewer`, `ensureSession`, `assertSameOrigin`, `InkLine`, `Seal`, `Sage` (F1.5a).
- `sse.ts`: `createSseParser(onEvent: (e: {event: string; data: unknown}) => void) -> { push(chunk: string): void; end(): void }`. Incremental: it handles events split across chunks, `\r\n`, comment lines (`:`), multi-line `data:`, and ignores unknown fields. `end()` with a partial event discards it.
- `topics.ts`: `TOPICS: {code, label, glyph, hint}[]` (table in Global Constraints).
- `qaText.ts`:
  - `reasonMessage(reason: string) -> {text, href?, cta?}`:

    | Reason | Text | Link |
    |---|---|---|
    | `no_credits` | "Con đã hết lượt." | `/vi/mua` "Nạp lượt" |
    | `free_quota` | "Hôm nay con đã hỏi đủ câu miễn phí." | `/vi/mua` |
    | `daily_cap` | "Hôm nay con hỏi nhiều rồi, mai ta luận tiếp." | |
    | `account_required` | "Con cần đăng nhập để dùng lượt." | `/vi/dang-nhap?next=/vi/hoi` |
    | `no_profile` | "Con lập lá số trước đã." | `/vi` |
    | `reading_in_progress` | "Câu này ta đang luận, con chờ chút." | |
    | `reading_conflict`, `reading_refunded` | "Con gửi lại câu hỏi nhé." | |
    | `unauthenticated` | "Phiên đã hết, con đăng nhập lại nhé." | |
    | default | "Lão Thiên Cơ cần một lát." | |

  - `qaStatusLabel`:

    | Status | Label |
    |---|---|
    | `ANSWERED` | Đã luận |
    | `PENDING`, `RETRYING` | Đang soạn |
    | `REFUND_PENDING` | Đang hoàn lượt |
    | `REFUNDED` | Đã hoàn lượt |
    | `REFUSED` | Ta không luận câu này |
    | `FAILED` | Chưa luận được |

  - `AI_LABEL = "Nội dung do AI biên soạn từ kho tư liệu."`
- `POST /api/qa/readings`: `assertSameOrigin`, then `ensureSession()` (FREE works for guests). Body `{reading_id, kind, topic, question, ai_consent?}`:
  - Validate uuid, kind, topic and 5–500 chars → 400 `{reason:"invalid", field}`.
  - Without the `tv_ai_ok` cookie and with `ai_consent !== true` → 400 `{reason:"ai_consent_required"}`. When `ai_consent` is true, set the cookie.
  - Call `qa.askStream(bearer, {…, lang:"vi"}, req.signal)`, with `AbortSignal.any([req.signal, AbortSignal.timeout(QA_STREAM_TIMEOUT_MS)])`.
  - 200 → `new Response(upstream.body, {headers: {"content-type":"text/event-stream; charset=utf-8", "cache-control":"no-cache, no-transform", "x-accel-buffering":"no"}})`.
  - `UpstreamProblem` → same status + `{reason}`; `UpstreamError` → 503 `{reason:"unavailable"}`.
- `POST /api/qa/readings/[id]/follow-ups`: the same with `{message_id, question}`; account session required.
- `GET /api/qa/readings/[id]`: → `{status, follow_ups_left}`.
- `QaAsk` (client) props: `{ canPay: boolean; freeLeft: number; needsConsent: boolean; isGuest: boolean }`.
  - Two submit buttons: "Dùng 1 lượt để hỏi" (PAID, shown when `canPay`) and "Hỏi miễn phí (còn {n} câu hôm nay)" (FREE, disabled at 0). A guest sees FREE plus a link "Đăng nhập để nạp lượt".
  - Under it: "Kèm 2 câu hỏi nối tiếp trong cùng chủ đề." (PAID only).
  - On submit: create `reading_id` once, disable the form, POST, and feed the response into the parser:
    - `meta` → remember `follow_ups_left`;
    - `delta` → `InkStream.append(text)`;
    - `done` → seal + disclaimer + `AI_LABEL` + link "Xem lại" `/vi/hoi/{id}` + `FollowUpBox` when PAID and `follow_ups_left > 0`;
    - `refused` → the text + disclaimer;
    - `queued` → the text + "Ta sẽ báo trong Lịch sử khi bài luận xong." + link;
    - `busy` → the text;
    - `error` → default.
  - A non-OK response → `reasonMessage`. A reader exception or end without a terminal event → the drop message from Review Focus 2.
- `InkStream` (client): `{ sentences: string[]; done: boolean }`. New words ink in at 60 ms/word (same CSS as `InkLine`); it auto-scrolls with `scrollIntoView({block:"nearest"})`. "Hiện hết" reveals everything; reduced motion → everything at once. The `Seal` shows when `done` and all words are revealed.
- `/vi/hoi`:
  - viewer without a profile → the "Con lập lá số trước đã." CTA;
  - else the `Sage` (`mood="read"`), heading "Con muốn hỏi về điều gì?", `TopicGrid` (radio group, 2 columns, glyph in Ma Shan Zheng), question `<textarea>` with a counter "0/500", the consent checkbox when `needsConsent`, and `QaAsk`.
  - `freeLeft` comes from `qa.freeQuota` (0 on error).
- `/vi/hoi/[readingId]`:
  - `getReading` → `QaMessages`: the question in `.ask` style; the answer as paragraphs, static, no animation; the seal on ANSWERED; `notice`, `disclaimer`, `AI_LABEL`.
  - `FollowUpBox` when PAID && ANSWERED && `follow_ups_left>0` ("Còn {n} câu hỏi nối tiếp trong chủ đề này.").
  - `ReadingStatusPoller` when PENDING/RETRYING/REFUND_PENDING: polls every 15 s and calls `router.refresh()` when the status changes. This is the in-app notification for queued answers and refunds.
  - 404 → `notFound()`.
- `/vi/lich-su`:
  - the list, newest first: topic label + glyph, `formatDateVn`, kind ("Lượt"/"Miễn phí"), status label, "Còn {n} câu nối tiếp" for PAID; each row links to `/vi/hoi/{id}`;
  - empty → "Con chưa hỏi câu nào.";
  - "Xóa toàn bộ lịch sử hỏi" in a `<dialog>` → `deleteHistoryAction`: 409 → "Còn câu đang soạn, con chờ xong rồi xóa nhé."

- [ ] **Step 1: Failing tests.**
  - `sse`:
    - one event split over 3 chunks;
    - two events in one chunk;
    - CRLF;
    - a comment line ignored;
    - `end()` drops a partial event;
    - Vietnamese text intact across a chunk split inside a multibyte char (feed via `TextDecoder` stream mode in `QaAsk`; test the decoder+parser pairing).
  - `askRoute`:
    - foreign Origin → 403;
    - bad uuid → 400 `invalid`;
    - missing consent → 400 `ai_consent_required`;
    - with consent the cookie is set;
    - upstream SSE is piped with the exact headers, and the body bytes are equal;
    - upstream 409 `no_credits` → 409 `{reason:"no_credits"}`;
    - `req.signal` abort aborts the upstream fetch (assert on the mocked fetch signal);
    - `lang:"vi"` is added server-side.
  - `QaAsk`, with a mocked fetch returning a `ReadableStream`:
    - deltas render and `done` shows the seal + `AI_LABEL`;
    - double click → `fetch` called once;
    - stream error → the drop message with a link to `/vi/hoi/<id>`;
    - 409 `free_quota` → message + `/vi/mua` link;
    - a guest has no PAID button.
  - `InkStream`: reduced motion → all words visible; "Hiện hết" → all visible + seal.
  - `FollowUpBox`: hidden at 0 left; posts `message_id` once.
  - `topics`: 6 entries in contract order.
- [ ] **Step 2: Run** `npm test` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: E2E** `qa.spec.ts`:
  1. Guest with a chart → `/vi/hoi` → choose Sự nghiệp, ask FREE (consent ticked) → 3 sentences appear, then "Hiện hết" → seal and AI label.
  2. Logged-in account → PAID ask → answer → follow-up → "Còn 1 câu…" on `/vi/hoi/<id>`.
  3. A question with "lỗi" → queued text; the history row reads "Đang soạn".
  4. `/vi/lich-su` lists both; delete all → empty.
  5. 375 px with no horizontal scroll on `/vi/hoi`.
- [ ] **Step 5: Gates** → green.
- [ ] **Step 6: Commit** `feat(web): Hỏi thầy streaming Q&A, follow-ups and history`.

---

### Task 6: Sharing — public chart link, image card, channels

**Files:**
- Create: `src/app/vi/la-so/shareActions.ts`, `src/components/SharePanel.tsx`, `src/app/vi/chia-se/[shareId]/{page.tsx, not-found.tsx}`, `src/app/vi/chia-se/[shareId]/the.png/route.tsx`, `src/lib/shareText.ts`.
- Create: `src/fonts/{Charm-Bold.ttf, NotoSerif-Regular.ttf, OFL.txt}` (from the Google Fonts GitHub repo, OFL 1.1, committed so the card build needs no network).
- Tests: `src/lib/shareText.test.ts`, `src/components/{SharePanel,Board}.test.tsx` (public variant), `src/app/vi/chia-se/sharePage.test.tsx`, `src/app/vi/chia-se/card.test.ts`, `e2e/share.spec.ts`.
- Modify: `src/components/Board.tsx` (prop `variant?: "owner" | "public"`; `public` omits the "Âm lịch d/m" line), `src/app/vi/la-so/page.tsx` (SharePanel below the board), `e2e/stub/identity.mjs` (share endpoints).

**Interfaces:**
- Consumes: `identity.getShare/createShare/deleteShare/resolveShare`, `getChart`, `getBasicReading`, `cfg().webOrigin`.
- `shareText.ts`:
  - `summaryLine(reading: BasicReading) -> string`: the first sentence of the `menh` section's first line (split on `. `/`! `/`? `), trimmed to ≤ 120 chars on a word boundary with "…".
  - `shareUrl(shareId) = cfg().webOrigin + "/vi/chia-se/" + shareId`.
  - `facebookShareUrl(url) = "https://www.facebook.com/sharer/sharer.php?u=" + encodeURIComponent(url)`.
- `createShareAction()` / `deleteShareAction()` → `revalidatePath("/vi/la-so")`.
- `SharePanel` props `{ share: {share_id} | null; summary: string }`:
  - none → "Tạo link công khai" + the note "Người xem thấy lá số và luận giải cơ bản. Ngày dương lịch được ẩn.";
  - active → read-only link field and buttons:
    - "Gửi qua Zalo, Messenger…" (`navigator.share({title, text: summary, url})`, rendered only when `navigator.share` exists);
    - "Facebook" (`facebookShareUrl`, `target=_blank rel="noopener noreferrer"`);
    - "Sao chép link" (clipboard + `aria-live` "Đã chép");
    - "Tải ảnh" (`<a href="/vi/chia-se/{id}/the.png" download="la-so-tu-vi.png">`);
    - "Tắt link".
- `/vi/chia-se/[shareId]`:
  - `resolveShare` → 404 → `not-found.tsx` ("Link này đã tắt hoặc không còn.");
  - the header line "{Nam|Nữ} · giờ {Chi} · năm {can chi}" only;
  - `Board variant="public"`, Dialogue (basic reading), the house ad CTA "Lập lá số miễn phí" → `/vi`.
  - `generateMetadata`: title "Lá số tử vi · năm {can chi} · Tử Vi Đẩu Số", description `summaryLine`, `openGraph.images: [{url: "/vi/chia-se/{id}/the.png", width: 1080, height: 1920}]`, `robots: {index: false, follow: false}`.
  - `export const dynamic = "force-dynamic"`.
- `the.png` route: `ImageResponse` 1080×1920 on the lacquer gradient (`#2E100C → #1A0A08`):
  - the brand "Tử Vi Đẩu Số" in Charm Bold;
  - a 4×4 mini board (12 cells: palace name + major star names, centre "năm {can chi}");
  - the summary line in Noto Serif, and the footer "Xem lá số của bạn tại Tử Vi Đẩu Số".

  Fonts are read with `fs.readFile` from `src/fonts` (`export const runtime = "nodejs"`); copy `src/fonts` into the standalone output via `outputFileTracingIncludes`. Headers `Cache-Control: public, max-age=3600`. 404 when the share is revoked.

- [ ] **Step 1: Failing tests.**
  - `summaryLine`: first sentence; 200-char input → ≤ 121 chars ending "…"; no `menh` → "".
  - `Board`: `variant="public"` has no text matching `/Âm lịch/`; owner still has it.
  - `sharePage`, with fixture chart/reading and mocked `resolveShare`:
    - the rendered HTML contains no lunar `d/m`, no birth year digits and no zone;
    - it contains "năm {can chi}";
    - unknown → `notFound`.
  - `card`: the response `content-type` is `image/png`; the PNG IHDR width/height bytes = 1080/1920.
  - `SharePanel`: the share button is hidden without `navigator.share`; copy calls the clipboard; the Facebook href is encoded.
- [ ] **Step 2: Run** `npm test` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: E2E** `share.spec.ts`:
  1. Cast a chart → "Tạo link công khai" → the link matches `/vi/chia-se/[A-Za-z0-9_-]{22}$`.
  2. Open it in a new context (no cookies) → 12 palaces; the page text has no `1990` and no `Âm lịch`; `og:image` meta present.
  3. `GET …/the.png` → 200 `image/png`.
  4. "Tắt link" → the old URL shows "Link này đã tắt".
- [ ] **Step 5: Gates** → green.
- [ ] **Step 6: Commit** `feat(web): public chart share link and image card`.

---

### Task 7: SEO knowledge pages, metadata, sitemap, robots

**Files:**
- Create: `src/lib/knowledge.ts`, `src/app/vi/kien-thuc/page.tsx`, `src/app/vi/kien-thuc/cung/[slug]/page.tsx`, `src/app/vi/kien-thuc/sao/[slug]/page.tsx`, `src/app/sitemap.ts`, `src/app/robots.ts`.
- Tests: `src/lib/knowledge.test.ts`, `src/app/seo.test.ts`, `e2e/seo.spec.ts`.
- Modify: `src/app/layout.tsx` (`metadataBase: new URL(cfg().webOrigin)`, title template `"%s · Tử Vi Đẩu Số"`); `export const metadata = { robots: { index: false } }` on private pages (`la-so`, `hoi/**`, `lich-su`, `tai-khoan/**`, `thanh-toan/**`, `quan-tri`, `dang-nhap/**`); `src/app/vi/page.tsx` (link "Kiến thức tử vi"); `services/web/Dockerfile` (build stage `COPY knowledge/tuvi /knowledge/tuvi` + `ENV KNOWLEDGE_DIR=/knowledge/tuvi`); `.github/workflows/ci.yml` (web path filter also `knowledge/tuvi/**`).

**Interfaces:**
- `knowledge.ts`:
  - `loadPalaces() -> KnowledgePage[]` and `loadStars() -> KnowledgePage[]`, where `KnowledgePage = {key: string; slug: string; name: string; content: string}`.
  - They read `${KNOWLEDGE_DIR}/doctrine/palace.vi.jsonl` (keys exactly `tuvi:palace=<Key>`) and `doctrine/star.vi.jsonl` (`tuvi:star=<Key>`), keeping only `status` absent or `"approved"`. The order is contract order: palaces Menh…HuynhDe as in the file; stars in file order.
  - `name` comes from a fixed map:
    - palaces: Mệnh, Phụ Mẫu, Phúc Đức, Điền Trạch, Quan Lộc, Nô Bộc, Thiên Di, Tật Ách, Tài Bạch, Tử Tức, Phu Thê, Huynh Đệ;
    - stars: Tử Vi, Thiên Cơ, Thái Dương, Vũ Khúc, Thiên Đồng, Liêm Trinh, Thiên Phủ, Thái Âm, Tham Lang, Cự Môn, Thiên Tướng, Thiên Lương, Thất Sát, Phá Quân.
  - `slugify(name)`: NFD, strip marks, `đ→d`, lowercase, spaces → `-`. Examples: "Thiên Cơ" → `thien-co`, "Điền Trạch" → `dien-trach`.
- Pages are statically generated (`generateStaticParams`, `dynamicParams = false`):
  - index: two lists, "12 cung" and "14 chính tinh";
  - detail: `<h1>{name}</h1>`, content paragraph(s), "Các sao chính khác"/"Các cung khác" links, the house ad CTA "Lập lá số miễn phí để xem {name} trong lá số của con" → `/vi`;
  - `metadata` title `"Cung {name} trong tử vi"` / `"Sao {name} trong tử vi"`, description = the first 155 chars of content, `alternates.canonical`.
  - No external ads (§16).
- `sitemap.ts`: `/vi`, `/vi/kien-thuc`, the 12 + 14 detail pages and the 3 legal pages (31 URLs, absolute with `WEB_ORIGIN`).
- `robots.ts`: allow `/`; disallow `/api/`, `/vi/la-so`, `/vi/hoi`, `/vi/lich-su`, `/vi/tai-khoan`, `/vi/thanh-toan`, `/vi/quan-tri`, `/vi/dang-nhap`, `/vi/chia-se`; `sitemap: WEB_ORIGIN + "/sitemap.xml"`.

- [ ] **Step 1: Failing tests.**
  - `knowledge`:
    - 12 palaces, 14 stars (reads the real repo files);
    - slugs unique, matching `^[a-z]+(-[a-z]+)*$`;
    - `slugify("Điền Trạch")=="dien-trach"`;
    - a temp dir with a `draft` record → excluded.
  - `seo`:
    - the sitemap has 31 entries, all starting with `WEB_ORIGIN`;
    - robots disallows each private prefix;
    - the private pages' exported metadata has `robots.index === false` (import each page module).
- [ ] **Step 2: Run** `npm test` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: E2E** `seo.spec.ts`:
  - `/vi/kien-thuc/sao/thien-co` → 200, h1 "Thiên Cơ", `<link rel="canonical">`, CTA link to `/vi`;
  - `/sitemap.xml` contains `/vi/kien-thuc/cung/menh`;
  - `/robots.txt` contains `Disallow: /vi/la-so`.
- [ ] **Step 5: Gates** → green. `next build` output lists the 26 detail routes as static (●).
- [ ] **Step 6: Commit** `feat(web): SEO knowledge pages, sitemap and robots`.

---

### Task 8: Full-stack run, docs, PR

**Files:**
- Create: `services/web/e2e-stack/full-flow.spec.ts`, `services/web/playwright.stack.config.ts`, `scripts/smoke-web.sh` (extend the existing one if present; else create).
- Modify: `CLAUDE.md` (web command line: new env and the full-stack run), `deploy/local/.env.example` (web vars), `docs/superpowers/plans/2026-11-25-plan-f1-5b-web-full.md` (tick boxes only).

**Interfaces:**
- Consumes everything above; no new code interfaces.
- `full-flow.spec.ts` runs against the real compose stack (`baseURL http://localhost:3001`). It reads magic links from Mailpit (`GET http://localhost:8025/api/v1/messages` → newest → `GET /api/v1/message/{ID}` → the `Text` field, regex `/vi/dang-nhap/xac-nhan\?token=[A-Za-z0-9_-]+`). The flow:
  1. cast a chart as a guest;
  2. ask one FREE question (fake LLM) → `done`;
  3. log in by magic link → `/vi/lich-su` still shows the free question (merge);
  4. buy `CREDIT_1` → simulator → balance 2;
  5. ask PAID → answer;
  6. the account page shows the lock line;
  7. share → the public page opens without cookies.

  Local only (not in CI); run inside `mcr.microsoft.com/playwright:v1.65.1-noble` with `--network host`.

- [ ] **Step 1:** Write the spec and the config (`testDir: "./e2e-stack"`, no `webServer`, 1 worker, timeout 120 s).
- [ ] **Step 2: Stack up.** Run `docker compose -f deploy/local/docker-compose.yml --profile app up -d --build postgres redpanda mailpit chart-svc reading-tuvi identity-svc payment-svc web`. Leave lgtm, order-svc and notify-svc down to stay within ~3 GB. If an old `pgdata` lacks the `identity`/`payment` DBs, run `down -v` once.
- [ ] **Step 3: Smoke + full flow.**
  - `scripts/smoke-web.sh http://localhost:3001`: ready 200; `/vi` contains "Lão Thiên Cơ"; `/vi/kien-thuc` 200; `/robots.txt` 200; prints `OK`.
  - Then run `docker run --rm --network host -v "$PWD/services/web:/w" -w /w mcr.microsoft.com/playwright:v1.65.1-noble sh -c "npm ci && npx playwright test -c playwright.stack.config.ts"`. Expected: 1 passed.
  - `docker stats --no-stream` must show web < 256 MiB.
  - Run `docker compose … down` afterwards (keep it up only if Tuấn asks).
- [ ] **Step 4: All gates** (web command from `CLAUDE.md`, plus `mvn -q -pl services/identity-svc,services/reading-tuvi -am verify` if not already green in CI).
- [ ] **Step 5: Commit** `test(web): full-stack flow and docs`. Push the branch, open the PR and wait for `ci-gate` green; a failure triggers the `retro` skill, and registry/font flakes are re-run per the `CLAUDE.md` triage rule. Merge happens after the final whole-branch review (controller).

---

## Notes

**Out of scope (later plans):**
- Sharing an individual paid answer (§13.4 "chỉ chia sẻ khi người dùng bấm chia sẻ từng bài") needs a reading-tuvi share endpoint, which belongs in a small follow-up. This plan shares the chart and the basic reading only.
- Detailed daily view and yearly reading for passes (backend not built). Pass cards say "(sắp có)".
- Email notifications: answer ready, refund, renewal 3 days before, underpaid transfer. This plan does them in-app only.
- Full account deletion (identity has no endpoint; profile and Q&A history deletion are offered).
- PayPal UI (F3). en/zh (F3). Astro site (F2).
- External ads (§16, later and only on knowledge pages).
- Self-hosting all layout fonts with `next/font/local`, a follow-up from the 2026-11-25 lesson. Only the two share-card fonts are committed here.
- k8s manifests. When they exist, the prod overlay must set `DEPLOY_ENV=prod`, `COOKIE_SECURE=1`, and no `DEV_PAYMENT_SIMULATOR`.

**Tuấn's to-dos (do not block the merge):**
1. Have a lawyer review the three legal pages (privacy, terms, refund) and add the seller information required for the e-commerce notice (§15).
2. Put your account id in `ADMIN_ACCOUNT_IDS` (env or Sealed Secret, never Git) to use `/vi/quan-tri`.
3. When Google OAuth exists, add `${WEB_ORIGIN}/vi/dang-nhap/google` to `OIDC_REDIRECT_URIS` and set `GOOGLE_LOGIN=1`.

**Decisions taken in this plan (Claude's call):**
- The chart lives in the identity profile (guest or account), not an in-memory store. `/vi/la-so` has no id in its URL.
- The session cookie value is the identity session token (httpOnly, Lax, `__Host-` + Secure in prod). CSRF uses Next's action Origin check plus `assertSameOrigin` on route handlers.
- The live balance updates by polling every 3 s on the checkout page, not SSE.
- The admin page is gated by an `ADMIN_ACCOUNT_IDS` allow-list plus the server-held `ADMIN_API_TOKEN`; non-admins get 404.
- Guest Q&A history merges through a BFF → reading-tuvi internal call after login.
- Share links are stored in identity-svc; share pages are noindex; Zalo/Messenger go through the Web Share API.
- SEO scope is 12 cung + 14 chính tinh + legal pages, with a sitemap and robots.
- Notifications and the renewal reminder are in-app.
- The VietQR instructions are cached in the BFF process.
- The simulator is runtime-gated by `DEV_PAYMENT_SIMULATOR=1` and `DEPLOY_ENV!=prod`.
- The full-stack e2e is local-only.

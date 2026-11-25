# Plan F1.5a — web tử vi xem thử (local): lá số, luận cơ bản, "Hôm nay"

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Vietnamese user on the local stack opens `http://localhost:3000`, enters a birth, and sees the themed tử vi site from prototype v2: a lacquer 12-palace board, Lão Thiên Cơ reading the free basic reading as a dialogue with the ink-bleed effect and red seal, and the "Hôm nay" summary — all fed by chart-svc and reading-tuvi, with no accounts, payment or LLM.

**Architecture:** New service `services/web` (Next.js App Router, TypeScript, SSR). The browser talks only to the web app; server components and route handlers call chart-svc and reading-tuvi over the compose network (BFF — no CORS, no service URLs in the browser). A submitted birth is kept server-side in an in-memory store under a random id (`/vi/la-so/<id>`), never in the URL or logs. Visual language is ported from `docs/superpowers/specs/assets/2026-11-24-fortune-prototype-v2.html` (tử vi site only).

**Tech Stack:** Node 22, Next.js (latest stable 15.x at implementation time, exact-pinned via lockfile), React 19, TypeScript strict, CSS Modules (no UI framework), `next/font/google` (Charm, Noto Serif, Noto Serif Display), Vitest + Testing Library (unit/component), Playwright (e2e, CI only). Node runs only in Docker locally (`docker run node:22-alpine …`).

**Spec:** `docs/superpowers/specs/2026-11-24-fortune-platform-design.md` §6.1 (web), §7 (privacy), §13.1 (themed UI), §14.2 (Lão Thiên Cơ), §14.4 (animated reading, reduced motion, "hiện hết"), §14.5 (fonts), §15 (disclaimer). Prototype: `docs/superpowers/specs/assets/2026-11-24-fortune-prototype-v2.html`. APIs: `contracts/openapi/chart-svc.yaml`, `contracts/openapi/reading-tuvi.yaml`. Tuấn's order (2026-11-24): "build trước cho thành hình, chạy local, test thử luận giải đúng" — F1.5a is that preview; F1.5b adds accounts, purchase, Q&A, sharing, SEO.

## Global Constraints

- Scope: site **tử vi**, language **vi** only, routes under `/vi` (`/` redirects to `/vi`). No login, payment, Q&A, sharing, SEO pages, astro site — those screens are not built (no dead links to them).
- Calendar is `VN` for every submission (Vietnamese UI rule, spec §12.1); the form has no calendar switch.
- **Privacy:** birth date/time, zone, gender never appear in URLs, query strings, logs, error pages or HTML outside the user's own result page; the result page header shows only giới tính, giờ (địa chi) and năm can chi (from the chart's `yearCanChi`, not the solar year). Birth store: in-memory, random 128-bit id, TTL 2 h, max 1 000 entries (LRU).
- Server → upstream: `CHART_SVC_URL` (default `http://localhost:8082`), `READING_URL` (default `http://localhost:8083`), timeout 5 s each; upstream 400 → form shows the field errors in Vietnamese; upstream down/5xx/timeout → a themed page where Lão Thiên Cơ says he needs a moment (no technical text, HTTP 503).
- Health: `GET /health/live` and `GET /health/ready` return `{"status":"UP"}` (platform contract). Server logs are JSON lines with `timestamp, level, service, trace_id, span_id, message` (`service` = `web`; ids empty strings when no trace).
- Theme (tử vi, from prototype v2): lacquer/cinnabar/gold palette as CSS custom properties copied from the prototype's `:root` tử vi values; display font **Charm** (titles, character name, brand), body **Noto Serif**, small labels **Noto Serif Display**; the Lão Thiên Cơ SVG ported from the prototype's `sage()`.
- Reading animation (spec §14.4): lines appear one after another; each word fades in with an ink-bleed (blur → sharp, ~60 ms/word); a red seal stamps after the last section; respects `prefers-reduced-motion` (no animation, everything shown); "Hiện hết" button reveals all instantly; the view follows the line being revealed.
- Responsive: works at 375 px width with no horizontal scroll; the board stays 4×4 and scales.
- Disclaimer in the footer of every page ("Nội dung mang tính tham khảo văn hóa và giải trí.").
- Image: non-root, multi-arch, Next `output: "standalone"`, `ghcr.io/<owner>/web`; compose service `web`, profile `app`, port `127.0.0.1:3000`, `mem_limit: 256m`.
- Quality gates: `npm run lint` (eslint, next config), `npm run typecheck` (tsc --noEmit), `npm test` (Vitest, line coverage ≥ 80% for `src/lib/**`), Playwright e2e in CI.

## Review Focus

1. **Invalid or edge dates** — 30/02, year 1899/2101, time 23:40 (giờ Tý next day): the form rejects impossible dates client- and server-side with Vietnamese messages; 23:40 renders a chart (chart-svc applies the roll). Tests in Task 2.
2. **Upstream down** — reading-tuvi or chart-svc stopped: themed 503 page, no stack trace, nothing about birth in logs. Test in Task 2 and e2e in Task 6.
3. **Birth data leaking** — URL after submit is `/vi/la-so/<id>` only; reloading after TTL or with an unknown id shows "lá số đã hết hạn, con nhập lại nhé" (404 page), not an error. Test in Task 2.
4. **Reduced motion and "Hiện hết"** — with `prefers-reduced-motion: reduce` every line is visible immediately; "Hiện hết" during animation shows all and the seal. Tests in Task 5.
5. **Mobile 375 px** — no horizontal scroll on home, chart and reading; e2e asserts `document.documentElement.scrollWidth <= 375`. Test in Task 6.

---

### Task 1: Scaffold `services/web`, image, compose, CI

**Files:**
- Create: `services/web/{package.json, package-lock.json, next.config.ts, tsconfig.json, eslint.config.mjs, vitest.config.ts, Dockerfile, .dockerignore, src/app/{layout.tsx, page.tsx, vi/page.tsx}, src/app/health/{live,ready}/route.ts, src/lib/log.ts}`, tests `src/lib/log.test.ts`, `src/app/health/health.test.ts`
- Create: `.github/workflows/_node-service.yml` (inputs `dir`, `image`, `publish`; jobs: `npm ci`, lint, typecheck, test, build; image build multi-arch like `_rust-service.yml`, push on main), modify `.github/workflows/ci.yml` (path filter `services/web/**` → job `web`; `ci-gate` needs it), `deploy/local/docker-compose.yml` (service `web`), `CLAUDE.md` (repo map + command)

**Interfaces:**
- `log(level: "info"|"warn"|"error", message: string, fields?: Record<string,string>)` writes one JSON line to stdout with the mandatory fields.

- [ ] **Step 1:** Generate the app with the official create-next-app (TypeScript, App Router, ESLint, `src/`, no Tailwind) inside `docker run --rm -v "$PWD/services:/w" -w /w node:22-alpine npx create-next-app@<latest 15.x> web …`; pin every dependency to exact versions; add Vitest + Testing Library.
- [ ] **Step 2: Failing tests:** `log.test.ts` (one line, valid JSON, has the six mandatory keys, `service == "web"`); health route handlers return 200 `{"status":"UP"}`; `/` redirects to `/vi`.
- [ ] **Step 3:** Implement; Dockerfile (node:22-alpine build stage → standalone runtime, `USER node`, `EXPOSE 3000`, `HEALTHCHECK` none); compose service with env `CHART_SVC_URL=http://chart-svc:8082`, `READING_URL=http://reading-tuvi:8083`, depends on both.
- [ ] **Step 4:** All gates green in Docker (`docker run … npm ci && npm run lint && npm run typecheck && npm test && npm run build`). **Commit** `feat(web): scaffold Next.js web service`.

---

### Task 2: BFF — upstream clients, birth store, form action

**Files:** Create `src/lib/{upstream.ts, birthStore.ts, birthForm.ts, labels.ts}`, `src/app/vi/actions.ts`, `src/app/vi/la-so/[id]/{page.tsx, not-found.tsx}`, `src/app/vi/error.tsx`, tests `src/lib/*.test.ts`.

**Interfaces:**
- `type Birth = { birth: string /* YYYY-MM-DDTHH:mm */; zone: string; gender: "male"|"female"; calendar: "VN" }`
- `parseBirthForm(fd: FormData) -> { ok: true; birth: Birth } | { ok: false; errors: Record<"date"|"time"|"gender"|"zone", string> }` — Vietnamese messages; rejects impossible dates and years outside 1900–2100.
- `birthStore.put(b: Birth) -> string /* id */`, `birthStore.get(id) -> Birth | undefined` (TTL 2 h, LRU 1 000, `crypto.randomUUID`-strength id).
- `getChart(b) / getBasicReading(b) / getToday(b, date: string)` → typed results per the OpenAPI contracts, or throw `UpstreamError("unavailable")` / `UpstreamValidation(fields)`; 5 s timeout via `AbortSignal.timeout`.
- `labels.ts`: `levelLabel("Thuan"|"Binh"|"LuuY") -> "Thuận"|"Bình"|"Lưu ý"`, branch → `"Tý"…"Hợi"`, hour label for a branch (`"giờ Thìn"`).
- Zones offered: `Asia/Ho_Chi_Minh` (default, label "Việt Nam"), plus `Asia/Bangkok`, `Asia/Tokyo`, `Asia/Seoul`, `Asia/Shanghai`, `Asia/Taipei`, `Asia/Singapore`, `Australia/Sydney`, `Europe/Paris`, `Europe/Berlin`, `Europe/London`, `America/New_York`, `America/Chicago`, `America/Los_Angeles` with Vietnamese country/city labels.

- [ ] **Step 1: Failing tests:** form — `30/02/1994` → error on `date`; `1899` → error; `23:40` accepted; missing gender → error. Store — put/get roundtrip; expired after TTL (fake timers); evicts the oldest past 1 000. Upstream — 400 maps fields; 500 / network error / >5 s → `UpstreamError`; **no log line contains the birth string** (capture stdout). Page — unknown id → `notFound()`.
- [ ] **Step 2:** Implement; the server action validates, stores, `redirect("/vi/la-so/" + id)`; the result page fetches chart + basic reading + today (today's date in `Asia/Ho_Chi_Minh`) in parallel. **Commit** `feat(web): upstream clients, birth store and form action`.

---

### Task 3: Theme, layout, home page with Lão Thiên Cơ

**Files:** Create `src/styles/theme.css`, `src/components/{Sage.tsx, SiteHeader.tsx, SiteFooter.tsx, BirthForm.tsx}`, update `src/app/layout.tsx`, `src/app/vi/page.tsx`; tests `src/components/BirthForm.test.tsx`.

- [ ] **Step 1:** Port from the prototype (tử vi only): palette tokens, paper/lacquer backgrounds, the hero with 紫微斗數 calligraphy title in Charm, `Sage` SVG (from `sage()`, props `mood: "greet"|"read"`), header (brand), footer with the disclaimer.
- [ ] **Step 2: Failing test:** `BirthForm` renders fields (ngày sinh, giờ sinh, giới tính, nơi sinh with the zone list, default Việt Nam), shows server errors under the right field, submit button "Xin Lão Thiên Cơ xem giúp".
- [ ] **Step 3:** Implement with `useActionState`; native date/time inputs; accessible labels. **Commit** `feat(web): theme, layout and home form`.

---

### Task 4: The 12-palace board

**Files:** Create `src/components/Board.tsx`, `src/lib/boardLayout.ts`, tests `src/lib/boardLayout.test.ts`, `src/components/Board.test.tsx`.

**Interfaces:** `cellOf(branch) -> { row: 1..4, col: 1..4 }` — the traditional layout: Tỵ Ngọ Mùi Thân on the top row (left→right), Thìn and Dậu at row 2, Mão and Tuất at row 3, Dần Sửu Tý Hợi on the bottom row (left→right); the centre 2×2 holds the chart summary (âm lịch, năm can chi, giờ, cục, mệnh chủ, thân chủ).

- [ ] **Step 1: Failing tests:** `cellOf("Si") == {row:1,col:1}`, `cellOf("Shen") == {row:1,col:4}`, `cellOf("Chen") == {row:2,col:1}`, `cellOf("You") == {row:2,col:4}`, `cellOf("Mao") == {row:3,col:1}`, `cellOf("Xu") == {row:3,col:4}`, `cellOf("Yin") == {row:4,col:1}`, `cellOf("Hai") == {row:4,col:4}`; Board renders 12 palaces with name + can chi, major stars with brightness letter (M/V/Đ/B/H), mutagens as small seals (Lộc/Quyền/Khoa/Kỵ), "Tuần"/"Triệt" marks, Mệnh outlined in cinnabar, Thân marked "Thân"; adjective stars in a smaller muted line; clicking a palace scrolls to its reading section (`#cung-<key>`).
- [ ] **Step 2:** Implement (CSS grid, scales down at 375 px). **Commit** `feat(web): lacquer 12-palace board`.

---

### Task 5: Reading dialogue with ink effect, seal, and "Hôm nay"

**Files:** Create `src/components/{Dialogue.tsx, InkLine.tsx, Seal.tsx, TodayPanel.tsx}`, `src/app/vi/la-so/[id]/page.tsx` (compose board + today + dialogue), tests `src/components/{Dialogue,TodayPanel}.test.tsx`.

- [ ] **Step 1: Failing tests:** Dialogue renders every section title and line from a reading fixture (copy `services/reading-tuvi/src/test/resources` response shape; build the fixture by running the local stack once, privacy-safe sample birth); with reduced motion (mock `matchMedia`) all lines are visible at once and the seal is shown; "Hiện hết" during animation reveals all lines and the seal; each section has id `cung-<palaceKey>` (or `menh`, `than`, `cuc`); TodayPanel renders 4 areas in order Công việc, Đi lại, Tiền bạc, Sức khỏe with Vietnamese level labels and the opening/closing lines.
- [ ] **Step 2:** Implement: client component reveals lines sequentially (IntersectionObserver-free; a timer per word, CSS `filter: blur()` → 0 and opacity), auto-scrolls the current line into view (`scrollIntoView({block:"nearest"})`), the Sage sits beside the dialogue (`mood="read"`), today panel above the dialogue. **Commit** `feat(web): Lão Thiên Cơ dialogue and Hôm nay`.

---

### Task 6: e2e, local run, PR

**Files:** Create `services/web/e2e/{reading.spec.ts, stub-upstream.mjs, fixtures/*.json}`, `services/web/playwright.config.ts`, `scripts/smoke-web.sh`; modify `_node-service.yml` (e2e job: start the stub upstream + `next start`, run Playwright Chromium).

- [ ] **Step 1:** `stub-upstream.mjs` — a tiny Node HTTP server serving captured chart, basic reading and today fixtures on two ports, with a `?down` switch (env) returning 503. Playwright tests: submit the form → URL matches `/vi/la-so/[0-9a-f-]{36}` and contains no digits of the birth date; board has 12 palaces; dialogue reaches the seal after "Hiện hết"; today shows 4 areas; 375 px viewport has no horizontal scroll on home and result; upstream down → 503 page with Lão Thiên Cơ text.
- [ ] **Step 2:** `scripts/smoke-web.sh <url>` — `set -euo pipefail`; GET `/health/ready` 200; GET `/vi` contains "Lão Thiên Cơ"; prints `OK`.
- [ ] **Step 3:** Local: `docker compose -f deploy/local/docker-compose.yml --profile app up -d --build --no-deps postgres chart-svc reading-tuvi web`; smoke-chart, smoke-reading, smoke-web OK; `docker stats --no-stream` (web < 256 MiB); keep the stack **up** for Tuấn to try only if asked, otherwise `down -v`.
- [ ] **Step 4:** Push, PR, `ci-gate` green (failure → lesson per `retro`). Do NOT merge — controller merges after final review. **Commit** `test(web): e2e with stub upstream and smoke`.

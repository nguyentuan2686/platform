# Plan F2.2a — identity-svc and payment-svc separate per site (tuvi | astro)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Model rule (Tuấn, 2026-11-28):** this plan is auth + payment, so implementers and the final review run on Fable 5.2 (`model: "fable"`, effort medium). Per-task reviews stay on Sonnet.

**Goal:** One identity-svc and one payment-svc serve two brands that customers see as unrelated, each with its own accounts, sessions, magic-link mail, credit wallet, passes and prices. Tử vi behaviour, its API and its mail stay byte-for-byte the same.

**Architecture:**
- A site value `tuvi | astro` enters identity-svc on every request through the header `X-Site`, which only the web BFF can send: the routes are behind the platform internal-token filter, and Ingress exposes none of them.
  - When the header is missing the site is `tuvi`, so the current web keeps working with no change.
- The site is stored on `account`, `guest`, `session`, `magic_link` and `oidc_login`.
  - A session is valid only for its own site.
  - Accounts are unique per `(site, email)` and `(site, google_sub)`.
- The wallet, passes and the birth lock live under the account, so they separate automatically.
- The astro birth profile is a new table with its own endpoints.
- payment-svc keys prices by `(site, product_code, currency)`.
- The PaymentSucceeded consumer cross-checks `event.site` against `account.site`.

**Tech Stack:** Java 21, Spring Boot (existing), Flyway, JdbcClient, Testcontainers Postgres, ArchUnit, JaCoCo gate; no new dependencies.

**Spec:** `docs/superpowers/specs/2026-11-28-f2-chiem-tinh-design.md` §4.1.3 (mua hàng tách riêng mỗi trang), §4.2 (identity-svc, payment-svc), §2 anti-goal (customers must not see that the two sites are one system). Base spec: `docs/superpowers/specs/2026-11-24-fortune-platform-design.md` §6.2, §6.5, §13.3. Conventions: `CLAUDE.md`, `.claude/skills/java-service-conventions/SKILL.md`.

## Global Constraints

- **Site keys:** exactly `tuvi` and `astro` (lowercase ASCII), as in `payment-svc` `orders.site` and `PaymentSucceeded.data.site`. Domain type `dev.platform.identity.domain.Site { TUVI, ASTRO }` with `key()` and `Site.parse(String) -> Optional<Site>`.
- **`X-Site` header:**
  - missing or empty → `tuvi`;
  - any other value outside the two keys → 400 `validation-error`, field `X-Site`, message `"must be tuvi or astro"`.
  - Never read from the body or query.
- **Tử vi must not change:** every existing identity-svc and payment-svc test passes unmodified, except tests whose fixtures must add the site argument at a changed signature. `contracts/openapi/identity-svc.yaml` and `payment-svc.yaml` only gain optional things. The tử vi mail subject stays exactly `"Đăng nhập xem tử vi"`, and the body text and link path `/{lang}/dang-nhap/xac-nhan?token=` stay identical.
- **Existing rows** become `tuvi` in the migration (column default `'tuvi'`, then `CHECK (site IN ('tuvi','astro'))`). Never edit applied migrations; identity's next is `V7`, payment's next is `V3`.
- **Cross-site isolation:**
  - A session token issued for site A presents as "no session" (401, same as an unknown token) on site B.
  - The same email gets two independent accounts.
  - A magic link requested on A cannot be verified on B: it verifies as an invalid token, with the same error as today.
- **Rate limits:** magic-link per-address limits are counted per `(site, email)`. The global daily mail cap stays one shared cap (`identity.mail.daily-cap`), because it protects the SMTP relay.
- **Per-site config, all via env:**

  | Value | tuvi | astro |
  |---|---|---|
  | Web base URL | `WEB_BASE_URL` (existing) | `ASTRO_WEB_BASE_URL` |
  | Mail sender | `MAIL_FROM` (existing) | `ASTRO_MAIL_FROM` |
  | Login path | `/dang-nhap/xac-nhan` (constant, unchanged) | `ASTRO_LOGIN_PATH`, default `/login/verify` |
  | Mail brand | — | `ASTRO_BRAND`, default `Star Keeper` |

  The final brand and domain come in F2.5 (spec §4.1.2).
- **Astro mail text, exact:**
  - `vi` subject: `"Đăng nhập {brand}"`.
  - `vi` body: `"Nhấn vào liên kết để đăng nhập {brand}: {link}\n\nLưu ý: liên kết dùng một lần, hết hạn sau 15 phút.\n"`.
  - `en`, used when lang is `en`: subject `"Sign in to {brand}"`, body `"Open this link to sign in to {brand}: {link}\n\nThe link works once and expires in 15 minutes.\n"`.
  - Other langs fall back to `vi`.
  - The astro mail never contains "tử vi", "xemtuvingay" or the tử vi sender.
- **Astro topics** (consume `topic`): `TinhCach TinhDuyen SuNghiep TaiLoc SucKhoe GiaDao VanNam`. Tử vi topics are unchanged: the existing `domain.Topic` values. The account's site decides which set is valid. A wrong topic → 400 field `topic`.
- **Astro profile fields:**
  - `label` (1–40);
  - `birth` `"YYYY-MM-DDTHH:MM"` (local);
  - `zone` (IANA);
  - `lat` [−90, 90] and `lon` [−180, 180];
  - `placeLabel` (1–80);
  - `timeKnown` (bool);
  - `houseSystem` (`placidus|whole-sign|equal`, default `placidus`).

  Every field except `label` and `houseSystem` is encrypted at rest with the existing profile cipher and `key_id`. It is never logged and never sent to an LLM. The same birth lock rule as tử vi applies: `account.birth_locked_at` forbids changing birth, zone, lat, lon or timeKnown.
- **Payment:**
  - `price` primary key becomes `(site, product_code, currency)`.
  - Astro rows are seeded with the same amounts and `refundable_credits` as tử vi (VND 29000/119000/49000/399000; USD 1.19/4.99/1.99/15.99).
  - `GET /products?site=astro` returns astro prices. An order's amount comes from its own site's row.
- **Logs:** ids and site only. Never emails, tokens, birth data or coordinates (existing rule).
- **Docs:** this PR touches `contracts/`, `deploy/k8s/` and DB migrations, so `docs/system/index.html` gets updated with one dated "Lịch sử" line (CI job `system-docs`).
- **Gates:** `mvn -q verify` per service (Spotless, JaCoCo ≥ 80% domain+application, ArchUnit). Locally run only the affected module: `mvn -q -pl services/identity-svc -am verify`. Heavy suites go to CI.

## Review Focus

1. **A session from one site used on the other.** Expect 401 on `/me`, `/me/profile`, `/me/astro-profile` and `/me/entitlements`; never another site's data. Tests in Task 2.
2. **The same email on both sites.**
   - Signing in on astro creates a second account with its own signup bonus.
   - The tử vi account's credits, passes, profile and birth lock are untouched.
   - Tests in Task 3 (IT).
3. **A magic link from tuvi verified with `X-Site: astro`, and the reverse.** Expect `invalid token` with the same response as an unknown token, and nothing consumed. Tests in Task 3.
4. **A PaymentSucceeded whose `site` differs from the account's site** (a forged or buggy event).
   - No grant.
   - The event goes to the DLT with an error log carrying `order_id`, `account_id` and both sites.
   - Tests in Task 7.
5. **A legacy client with no `X-Site`** (current web): every existing flow behaves exactly as before. This is the unchanged test suite plus `scripts/smoke-identity-payment.sh` with no header. Task 9.

---

### Task 1: Identity schema per site (V7) and the `Site` type

**Files:**
- Create: `services/identity-svc/src/main/resources/db/migration/V7__site.sql`, `src/main/java/dev/platform/identity/domain/Site.java`, test `src/test/java/dev/platform/identity/domain/SiteTest.java`, IT `src/test/java/dev/platform/identity/adapter/out/persistence/SiteSchemaIT.java`.

**Migration V7 (exact intent):**
- Add `site text NOT NULL DEFAULT 'tuvi' CHECK (site IN ('tuvi','astro'))` to `account`, `guest`, `session`, `magic_link` and `oidc_login`.
- On `account`, drop the unique constraint on `email` and add unique `(site, email)`. Replace unique `google_sub` with unique `(site, google_sub)`.
- On `magic_link`, replace index `(email, created_at)` with `(site, email, created_at)`.
- No data rewrite beyond the default.

- [ ] **Step 1:** Write the failing tests.
  - `SiteTest`: `parse("tuvi")==TUVI`, `parse("astro")==ASTRO`, `parse("TUVI")` / `parse("")` / `parse("x")` empty, and `key()` round-trip.
  - `SiteSchemaIT` (Testcontainers, Flyway):
    - insert accounts `(tuvi, a@x.vn)` and `(astro, a@x.vn)` → both succeed;
    - a second `(tuvi, a@x.vn)` → unique violation;
    - `site='moon'` → check violation;
    - a row inserted without site reads back `tuvi`.
- [ ] **Step 2:** Run `mvn -q -pl services/identity-svc -am verify -Dit.test=SiteSchemaIT` → FAIL.
- [ ] **Step 3:** Implement V7 and `Site`.
- [ ] **Step 4:** Run the module verify → PASS. All existing ITs still pass, because the default keeps old inserts valid.
- [ ] **Step 5:** Commit `feat(identity): site column on accounts, guests, sessions, links`.

### Task 2: Site resolution and site-bound sessions

**Files:**
- Create: `adapter/in/web/SiteResolver.java`, a `HandlerMethodArgumentResolver` for a `Site` parameter from `X-Site` with the Global Constraints rules.
- Modify: `application/*SessionRepositoryPort` + `adapter/out/persistence/JdbcSessionRepository` (`issue(..., Site)`, `findActive(byte[] tokenSha, Site site, Instant now) -> Optional<Subject>`, SQL `... and site = :site`); `GuestUseCase` / `POST /guests` (guest created with the request site); every controller method that resolves the subject (`AuthController`, `MeController`, `ProfileController`, entitlements/ledger, share) takes `Site site` and passes it through.
- Test: `SiteResolverTest` (`@WebMvcTest`); extend the session repository IT; controller tests.

**Interfaces (Produces):**
- `Site` parameter available to every web handler.
- `SessionRepositoryPort.findActive(byte[] tokenSha256, Site site, Instant now)`.
- `GuestUseCase.create(Site site)`.

- [ ] **Step 1:** Write the failing tests.
  - No header → `tuvi`. `X-Site: astro` → astro. `X-Site: moon` → 400 problem, field `X-Site`, message `"must be tuvi or astro"`.
  - A guest created with `astro`, then its token used with no header (= tuvi) on `GET /me` → 401 problem identical to an unknown token.
  - The same token with `X-Site: astro` → 200 `kind: guest`.
  - The repository IT: `findActive(token, ASTRO)` for a tuvi session → empty.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement. The site is a method parameter, not a ThreadLocal. Keep `InternalTokenFilter` untouched.
- [ ] **Step 4:** Run the module verify → PASS, with existing controller tests unchanged.
- [ ] **Step 5:** Commit `feat(identity): X-Site header and site-bound sessions`.

### Task 3: Magic link and mail per site

**Files:**
- Create: `domain/SiteMail.java` (record `SiteMail(String from, URI webBaseUrl, String loginPath, String brand)`), `application/SiteMailSettings.java` (`SiteMail of(Site)`), `adapter/out/mail/MailTemplates.java` (subject and body per `(Site, lang)` with the exact texts in Global Constraints; tử vi texts moved verbatim from `SmtpMailerAdapter`).
- Modify:
  - `MagicLinkUseCase`:
    - `request(Site, Email, lang, guestId, clientIp)`;
    - link = `siteMail.webBaseUrl + "/" + lang + siteMail.loginPath + "?token=" + token`, where the tuvi `loginPath` is `/dang-nhap/xac-nhan`;
    - `verify(Site, rawToken, guestId)`.
  - `MagicLinkRepositoryPort`:
    - `lock(Site, Email)`, an advisory lock on `hashtext(site || ':' || email)`;
    - `requestTimesSince(Site, Email, Instant)`;
    - `save` stores the site;
    - `consume(byte[], Site, Instant)` matches the site.
  - `AccountRepositoryPort.upsert(Site, Email, Instant)` (`on conflict (site, email)`), `findByGoogleSub(Site, String)`, `linkGoogleSub`.
  - `MailerPort.sendMagicLink(Site, Email, URI, String lang)`; `SmtpMailerAdapter` sends from `SiteMail.from`.
  - `IdentityConfiguration`: build `SiteMailSettings` from `identity.web-base-url`, `identity.mail.from`, `identity.astro.web-base-url` (`ASTRO_WEB_BASE_URL`), `identity.astro.mail-from` (`ASTRO_MAIL_FROM`), `identity.astro.login-path` (`ASTRO_LOGIN_PATH`, default `/login/verify`) and `identity.astro.brand` (`ASTRO_BRAND`, default `Star Keeper`). Fail fast at startup if an astro URL or sender is blank.
  - `application.yml` keys.
- Test: `MagicLinkUseCaseTest`, `SmtpMailerAdapterTest`, `MagicLinkFlowIT` (Mailpit/GreenMail as today).

- [ ] **Step 1:** Write the failing tests.
  - `MailTemplates`: tuvi/vi gives exactly today's subject and body. Astro/vi with brand `Star Keeper` gives `"Đăng nhập Star Keeper"` and the exact body. Astro/en gives the English texts. Astro/zh-Hant falls back to vi. No astro text contains `tử vi`, `xemtuvingay` or the tuvi sender.
  - `MagicLinkUseCaseTest`:
    - the astro link is `https://astro.test/vi/login/verify?token=…` when `ASTRO_WEB_BASE_URL=https://astro.test`;
    - per-address limit counted per site: 3 requests for `a@x.vn` on tuvi do not limit astro (use the existing `MagicLinkPolicy` numbers);
    - verify of a tuvi link with `ASTRO` → `invalid token`, and the link stays unused.
  - `MagicLinkFlowIT`:
    - same email, magic link on tuvi then on astro → two account ids, each with signup bonus 1 credit;
    - the astro mail is from `ASTRO_MAIL_FROM` with the astro subject;
    - tuvi account credits are unchanged after the astro signup.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Module verify → PASS. Existing tuvi mail assertions are untouched.
- [ ] **Step 5:** Commit `feat(identity): magic link and mail per site`.

### Task 4: Google sign-in per site

**Files:**
- Modify: `OidcLoginUseCase` (`authorize(Site, ...)` stores the site in `oidc_login`; the callback reads it back and uses `findByGoogleSub(site, sub)` / `upsert(site, email)`); its controller; the tests.

- [ ] **Step 1:** Write the failing test: the same Google `sub` on tuvi and astro → two accounts. The callback state from an astro authorize creates the astro account even without an `X-Site` header on the callback, because the site comes from the stored state.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(identity): Google sign-in per site`.

### Task 5: Astro birth profile

**Files:**
- Create:
  - `db/migration/V8__astro_profile.sql`: `astro_profile(id uuid PK, guest_id uuid NULL UNIQUE REFERENCES guest, account_id uuid NULL UNIQUE REFERENCES account, label text CHECK (char_length BETWEEN 1 AND 40), birth_enc bytea NOT NULL, key_id smallint NOT NULL, house_system text NOT NULL DEFAULT 'placidus' CHECK (house_system IN ('placidus','whole-sign','equal')), created_at, updated_at, CHECK (num_nonnulls(guest_id, account_id) = 1))`.
  - `domain/AstroBirth.java`: record with validation per Global Constraints; `ProfileValidationException` with field names `birth.birth|birth.zone|birth.lat|birth.lon|birth.placeLabel|birth.timeKnown|houseSystem|label`.
  - `domain/AstroProfile.java`, `application/AstroProfileUseCase.java`, `application/AstroProfileRepositoryPort.java`, `adapter/out/persistence/JdbcAstroProfileRepository.java`, `adapter/in/web/AstroProfileController.java`, `AstroProfileRequest/Response`.
- Modify: `AccountMergePort` implementation, so the guest → account merge also moves the guest's astro profile; `contracts/openapi/identity-svc.yaml` (paths `/me/astro-profile` GET/PUT/DELETE with closed schemas, `X-Site` header parameter on every web path).

**Interfaces (Produces):**
- `GET/PUT/DELETE /me/astro-profile`. Only for `X-Site: astro`; for tuvi → 404 `not-found`, so tử vi has no new surface.
- The body is `{label, birth: {birth, zone, lat, lon, placeLabel, timeKnown}, houseSystem}`.
- The response adds `id`, `birthLocked`.

- [ ] **Step 1:** Write the failing tests:
  - domain validation for each bad field → its field name;
  - PUT then GET round-trip;
  - stored `birth_enc` is not plaintext (the IT reads the row and asserts the zone and coordinates are absent from the bytes);
  - lock: after `lockAccount`, a PUT changing lat → 409 `profile-locked`, while changing only `label`/`houseSystem` → 200;
  - tuvi site → 404;
  - guest astro profile merged into the account on magic-link verify;
  - contract test green.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement, reusing the profile cipher bean and `key_id`. Errors as existing profile errors.
- [ ] **Step 4:** Module verify → PASS.
- [ ] **Step 5:** Commit `feat(identity): astro birth profile`.

### Task 6: Credits consume topics per site

**Files:**
- Modify:
  - `InternalCreditController.ConsumeRequest.topic` becomes `String`;
  - the consume use case resolves the account's site and validates the topic against that site's set (tử vi = `domain.Topic` names; astro = the Global Constraints list in a new `domain.AstroTopic`);
  - the ledger stores the topic string as today;
  - `contracts/openapi/identity-svc.yaml` consume `topic` becomes a string with a description of both sets.
- Test: `InternalCreditControllerTest` and the use-case test.

- [ ] **Step 1:** Write the failing tests:
  - astro account + `TinhCach` → consumed;
  - astro account + `VanNam` → consumed;
  - tuvi account + `TinhCach` → 400 field `topic` (not in the tử vi set);
  - tuvi account + `TinhDuyen` → consumed, as today;
  - an unknown topic → 400.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS. reading-tuvi's `IdentitySvcClient` still sends enum names, so nothing changes there.
- [ ] **Step 5:** Commit `feat(identity): consume topics per site`.

### Task 7: PaymentSucceeded site cross-check

**Files:**
- Modify: `PaymentSucceededListener` / `PaymentGrantUseCase.grant(eventId, orderId, accountId, productCode, Site eventSite)`. Read `site` from the event. If the account's site differs: no grant, throw a non-retryable exception so the existing DLT path receives it, and log at error with `order_id`, `account_id`, `event_site` and `account_site`.
- Test: the listener/use-case tests and the existing consumer IT.

- [ ] **Step 1:** Write the failing tests:
  - astro event for a tuvi account → no ledger row, message in the DLT, log fields as listed;
  - matching site → grant, as today;
  - an event without `site` (old producer) → treated as `tuvi`, documented.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Module verify → PASS.
- [ ] **Step 5:** Commit `feat(identity): reject payment events for another site's account`.

### Task 8: payment-svc prices per site (V3)

**Files:**
- Create: `services/payment-svc/src/main/resources/db/migration/V3__price_site.sql`. Add `site text NOT NULL DEFAULT 'tuvi' CHECK (site IN ('tuvi','astro'))`, switch the PK to `(site, product_code, currency)`, and insert the astro rows copied from tuvi.
- Modify:
  - `PriceCatalogPort`: `price(String site, String productCode, String currency)`, `list(String site, String currency)`, `refundableCredits(String site, String productCode)`;
  - `JdbcPriceCatalog` SQL adds `and site = :site`;
  - `CatalogUseCase.list`, `CreateOrderUseCase.create` and `RefundUseCase` (the order's site) pass the site.
- Test: the use-case tests, `JdbcPriceCatalogIT` and the existing ITs.

- [ ] **Step 1:** Write the failing tests:
  - after V3, `list("astro","VND")` returns the 4 products with the tử vi amounts;
  - an astro order's amount comes from the astro row (test by changing the astro CREDIT_1 amount in the IT and creating an order);
  - an astro order code starts with `CT`;
  - the refund of an astro CREDIT_5 order uses the astro `refundable_credits`;
  - tuvi results are unchanged.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `mvn -q -pl services/payment-svc -am verify` → PASS.
- [ ] **Step 5:** Commit `feat(payment): prices per site`.

### Task 9: Deploy config, smoke, docs

**Files:**
- Modify:
  - `deploy/local/docker-compose.yml` (identity-svc env `ASTRO_WEB_BASE_URL=http://localhost:3002`, `ASTRO_MAIL_FROM=no-reply@astro.localhost`, `ASTRO_BRAND=Star Keeper`);
  - `deploy/local/.env.example` if it lists identity env;
  - `deploy/k8s/base/identity-svc/deployment.yaml` (env with placeholders);
  - `deploy/k8s/overlays/e2e/patches/identity-svc.yaml`;
  - `deploy/k8s/overlays/prod/apps/patches/identity-svc.yaml` (astro values = placeholder `https://astro.invalid` and `no-reply@astro.invalid` until F2.5; astro is not exposed in prod, so these are never mailed);
  - `scripts/smoke-identity-payment.sh`;
  - `docs/system/index.html` (one "Lịch sử" line plus the flow note "identity/payment phân biệt theo trang qua header X-Site");
  - `CLAUDE.md` (the smoke-identity-payment line mentions the astro leg).
- [ ] **Step 1:** Extend the smoke after the existing tuvi leg, which is unchanged and sends no header. Add an astro leg with `X-Site: astro`:
  1. guest, then a magic link for the **same email**, read from Mailpit.
  2. Assert the subject is `Đăng nhập Star Keeper` and the sender is `ASTRO_MAIL_FROM`.
  3. Log in.
  4. `GET /me` with the astro session and no header → 401.
  5. `CREDIT_5` order with `site=astro` → code `CT…`, then simulated transfer → astro credits 6.
  6. The tuvi account still has its own count (6 from the tuvi leg).
  7. Print `OK`.

  The script captures output before grepping (CLAUDE.md shell rule).
- [ ] **Step 2:** Run the low-memory subset from CLAUDE.md (`postgres redpanda mailpit identity-svc payment-svc`), then `scripts/smoke-identity-payment.sh` → `OK`. Then stop only those containers.
- [ ] **Step 3:** Run `docker run --rm -v "$PWD:/w" -w /w alpine/k8s:1.33.4 bash scripts/k8s-validate.sh` → ok, and `bash scripts/check-system-docs.sh` with the changed files → OK. Run `bash scripts/test/new-service.test.sh` if any registration file changed.
- [ ] **Step 4:** Commit `chore(identity,payment): per-site config, smoke astro leg, docs`. The PR is opened by the controller after the final review (Fable 5.2, medium). The main session lands it.

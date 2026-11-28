# Plan F2.2b — extract `libs/java/reading-core` from reading-tuvi (no behaviour change)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Model rule (Tuấn, 2026-11-28): the final review runs on Fable 5.2 at effort medium. Task 5 moves the charge/refund and PII parts of Q&A, so it is security-sensitive and its implementer also runs on Fable 5.2 at effort medium.

**Goal:** Move the parts of reading-tuvi that a second reading service needs into a shared Maven library `libs/java/reading-core`, with explicit plug points per site, so that F2.3 `reading-astro` reuses them instead of copying them (spec K1, K3 rejected). reading-tuvi's HTTP API, database, outputs and prompts stay exactly the same.

**Architecture:**
- New jar module `dev.platform:reading-core`, built like `libs/java/platform-starter`: a plain jar, Spring deps `optional`, no boot repackage.
- Packages:
  - `dev.platform.reading.core.domain`: pure records and policies;
  - `dev.platform.reading.core.application`: use cases and ports;
  - `dev.platform.reading.core.adapter`: JDBC, the OpenAI-compatible HTTP adapter, AES-GCM.

  This naming keeps the root JaCoCo includes (`*.domain*`, `*.application*`) meaningful.
- reading-tuvi depends on the lib and keeps the site-specific parts:
  - knowledge key grammar, topics, persona, prompt, Vietnamese guard and filter, chart mapping;
  - number check, routes, Spring wiring.
- The Q&A orchestration is split into a generic pipeline in core and a `QaSitePlugin` implemented by tử vi.

**Tech Stack:** Java 21, Spring Boot (existing), Maven multi-module, JdbcClient, Testcontainers, ArchUnit, JaCoCo.

**Spec:** `docs/superpowers/specs/2026-11-28-f2-chiem-tinh-design.md` §4.2 (reading-core with plug points: kiểu khóa kho, danh sách ngôn ngữ và chủ đề, persona và prompt, bộ lọc đầu ra theo ngôn ngữ), §5 K1/K3. Conventions: `CLAUDE.md`, `.claude/skills/java-service-conventions/SKILL.md`. The repository facts this plan relies on were gathered on 2026-11-28 from main `9b27835`. If reading-tuvi changed since then, re-check the class lists below before Task 1.

## Global Constraints

- **Zero behaviour change for tử vi:**
  - every existing reading-tuvi test passes; only import and package lines may change;
  - `contracts/openapi/reading-tuvi.yaml` is unchanged;
  - no Flyway migration in reading-tuvi;
  - `PromptBuilder.PROMPT_VERSION` stays `3` and prompt text is byte-identical (pinned by Task 1);
  - `scripts/smoke-reading.sh` and `scripts/smoke-qa.sh` (CI kind e2e) stay green.
- **Moves to core**, with tests moving alongside, tests unchanged apart from package:
  - `application/llm/*` and `adapter/out/llm/*`;
  - `AesGcmQaCipherAdapter`;
  - `JdbcLlmUsage`, `JdbcQaAnswerCache`, `JdbcQaRepository`;
  - `QaJobs`;
  - the generic `application/qa/*`: `QaStore`, `QaRepositoryPort`, `QaAnswerCachePort`, `QaTextCipherPort`, `QaSettings`, `QaStatus`, `QaKind`, `RetryBackoff`, `QaRetryUseCase`, `QaRetentionUseCase`, `QaMergeUseCase`, `PromptCodec`, `IdentitySubject`, `ConsumeOutcome`, `RefundOutcome` and the generic exceptions;
  - the generic `domain/qa/*`: `PiiScrubber`, `QuestionNormalizer`, `Sentinel`, `AnswerLimits`, `FilterResult`, `GuardResult`, `Prompt`, `Turn`, `RefusalReason`;
  - the knowledge importer and repository: `JsonlKnowledgeImporter`, `JdbcKnowledgeRepository`, `KnowledgeImportRunner`, `ImportResult`, `KnowledgeImportException`.
- **Stays in reading-tuvi:**
  - `KnowledgeKey`, `QaTopic`, `PromptBuilder` / `PromptKind`, `QaFrames`, `QaContextBuilder` and the tử vi `domain/*`;
  - `QuestionGuard`, `AnswerFilter`, `SafetyLexicon` (Vietnamese-specific);
  - `TimingIntent`, `TimingLines`, `TopicPalaces`;
  - `FakeLlmProvider` text (a fake provider stays per service; core keeps only the `LlmProviderPort` contract);
  - number check, controllers, `adapter/config/*`, `ChartSvcClient` / `ChartMapper`, `IdentitySvcClient`.
- **Topic in core is a string key.** `QaRepositoryPort` and the pipeline use `String topicKey`, validated by the plugin. reading-tuvi maps `QaTopic` ↔ `name()` at its edge. DB values are identical to today because the tử vi CHECK uses the enum names.
- **Plug points** live in `dev.platform.reading.core.application.site`:
  - `KnowledgeKeyPolicy`: `boolean valid(String key)`, `Set<String> langs()`, `Set<String> topics()`.
  - `QaSitePlugin`:
    - `GuardResult guard(String question, String lang)`;
    - `Prepared prepare(AskCommand cmd)`: chart lookup plus context, site-specific, may throw `UpstreamUnavailableException`;
    - `Prompt prompt(Prepared p, List<Turn> history, String lang)`;
    - `FilterResult filter(String answer, String lang)`;
    - `int promptVersion()`;
    - `String notEnoughSentinel()`.

  Each `Prepared` / `AskCommand` type in core holds only generic fields (subject, topic key, question, lang, chart hash, opaque context string). The site keeps its own richer types behind the plugin. Final method names are the implementer's choice as long as these responsibilities are the seams and Task 5's tests pin them.
- **Spring wiring stays in each service.** Core has no `@Configuration` and no auto-configuration, so reading-tuvi's `adapter/config/*` keeps building the beans.
- **Every Java Dockerfile copies the new pom:** `services/{order-svc,reading-tuvi,identity-svc,payment-svc}/Dockerfile` and `templates/java-service/Dockerfile` get `COPY libs/java/reading-core/pom.xml libs/java/reading-core/` next to the platform-starter line, plus `libs/java/reading-core/target` in the cleanup `rm -rf`. `scripts/test/new-service.test.sh` must stay green: run it.
- **Gates:** root `mvn -q verify` in CI. Locally run only `mvn -q -pl libs/java/reading-core,services/reading-tuvi -am verify`, and run Testcontainers ITs only for moved adapters. ArchUnit in core:
  - `domain` imports no Spring, Jackson or Jakarta;
  - `application` does not import `adapter`;
  - no field injection;
  - nothing in core imports `dev.platform.reading.tuvi`.

## Review Focus

1. **Prompt drift.** A refactor that reorders a prompt line or changes whitespace silently changes LLM behaviour while every functional test passes. Task 1 pins the exact system and user prompt strings for representative inputs. Those tests must pass unchanged after Task 5.
2. **Charge/refund ordering.** "Charge after guard and chart, before the LLM; refund on failure after retries" must hold through the pipeline split. The existing `QaServiceTest` cases cover it and must pass unchanged, and Task 5 adds a pipeline test with a fake plugin.
3. **PII.** `PiiScrubber` still runs on the question before it is stored or sent, for every plugin. Task 5's pipeline test uses a plugin that echoes the question, and asserts that an email and a phone in the question never reach the prompt port.
4. **Docker builds.** A missing `COPY` for the new pom breaks all Java images in CI only. Task 2 adds it everywhere, and the template test checks the template.
5. **Number check.** It imports `IdentitySubject` / `IdentityPort` and `KnowledgeKey`. After the moves it still compiles, and `NumberCheckServiceTest` and `JdbcNumberUsageIT` stay green.

---

### Task 1: Characterization tests (reading-tuvi, before any move)

**Files:**
- Create: `services/reading-tuvi/src/test/java/dev/platform/reading/tuvi/domain/qa/{PromptBuilderCharacterizationTest, QuestionGuardCharacterizationTest, AnswerFilterCharacterizationTest, PiiScrubberTest}.java` and fixtures under `src/test/resources/characterization/`.

- [ ] **Step 1:** Write tests that pin today's behaviour. They pass on the current code, because they are characterization tests:
  - `PromptBuilder`: for 3 representative inputs (one per `PromptKind`, including a VanNam timing prompt), the exact system and user strings equal committed fixture files, `PROMPT_VERSION == 3`, and the sentinels are `KHONG_DU_TU_LIEU` and `NGOAI_PHAM_VI`.
  - `QuestionGuard`: a table of ≥ 25 questions with the expected `RefusalReason` or pass. Include the carve-outs `"dễ bị bệnh"`, `"khi nào có con"`, and `"cúng"` vs `"cung"`.
  - `AnswerFilter`: leak literals `TƯ LIỆU` / `CÂU HỎI` are rejected, a clean answer passes, and a `SafetyLexicon` term is rejected.
  - `PiiScrubber`: emails, Vietnamese mobile formats (`09xx`, `+84`, with spaces and dots) and an address-free question unchanged.
- [ ] **Step 2:** Generate each prompt fixture once from the current code. Review it by eye and commit it. Run `mvn -q -pl services/reading-tuvi -am verify` → PASS.
- [ ] **Step 3:** Commit `test(reading-tuvi): pin prompts, guard, filter and PII scrubbing before the core extraction`.

### Task 2: `libs/java/reading-core` module skeleton

**Files:**
- Create: `libs/java/reading-core/pom.xml` (parent `platform-parent`, `relativePath ../../../pom.xml`, deps as needed by the moved code: spring-jdbc, jackson, platform-starter, all `optional` where Spring; test deps junit, assertj, testcontainers-postgresql, archunit), `src/test/java/dev/platform/reading/core/ArchitectureTest.java` (+ non-vacuity fixture like reading-tuvi's `ArchitectureRulesTest`).
- Modify: root `pom.xml` (`<module>libs/java/reading-core</module>` after platform-starter); the 4 Java Dockerfiles + `templates/java-service/Dockerfile` (COPY + cleanup); `services/reading-tuvi/pom.xml` (dependency on `reading-core`).

- [ ] **Step 1:** Add the module with one placeholder class, `dev.platform.reading.core.domain.package-info.java`. Write the ArchUnit test, which must fail on its fixture and pass on core.
- [ ] **Step 2:** Run `mvn -q -pl libs/java/reading-core,services/reading-tuvi -am verify` → PASS. Run `bash scripts/test/new-service.test.sh` → ok, so the template pins still match.
- [ ] **Step 3:** Build the reading-tuvi image once locally (`docker compose -f deploy/local/docker-compose.yml build reading-tuvi`) to prove the Dockerfile COPY works. Run it in the background; it is slow on this machine.
- [ ] **Step 4:** Commit `build: reading-core library module`.

### Task 3: Move the LLM gateway

**Files:**
- Move to `libs/java/reading-core/src/main/java/dev/platform/reading/core/`:
  - `application/llm/*` → `application/llm/`;
  - `adapter/out/llm/{OpenAiCompatibleLlmAdapter, OpenAiCompatibleSettings}` → `adapter/llm/`.
- Move their tests (`LlmGatewayTest`, `OpenAiCompatibleLlmAdapterTest`) to the core module.
- `FakeLlmProvider` / `FakeMode` and their tests stay in reading-tuvi, implementing the core `LlmProviderPort`.
- Modify: reading-tuvi imports and `adapter/config/LlmConfiguration`.

- [ ] **Step 1:** Move the code with `git mv` so history follows. Fix packages and imports.
- [ ] **Step 2:** Run both modules' verify → PASS, with the same test count as before the move (record both counts in the report).
- [ ] **Step 3:** Commit `refactor(reading): LLM gateway into reading-core`.

### Task 4: Move the generic Q&A plumbing and the knowledge importer

**Files:**
- Move the generic `application/qa/*`, generic `domain/qa/*`, `AesGcmQaCipherAdapter`, `JdbcLlmUsage`, `JdbcQaAnswerCache`, `JdbcQaRepository`, `QaJobs` and the knowledge importer/repository classes listed in Global Constraints to core (`application/qa`, `domain/qa`, `adapter/crypto`, `adapter/persistence`, `adapter/schedule`, `adapter/knowledge`), with their tests and ITs.
- Create: `core.application.site.KnowledgeKeyPolicy`.
- In reading-tuvi, create `adapter/config/TuviKnowledgeKeyPolicy` wrapping `KnowledgeKey`'s regex, `langs = {vi}` and the existing topic list.

**Interfaces (Produces):**
- `JdbcQaRepository` and `QaRepositoryPort` take `String topicKey`.
- `JsonlKnowledgeImporter(KnowledgeKeyPolicy policy, ...)` validates through the policy instead of `new KnowledgeKey(...)`.

- [ ] **Step 1:** Write the failing test: the core importer with a fake policy accepting `astro:planet=Venus:sign=Taurus` and lang `en` imports that line. With the tử vi policy, the same line is rejected with the same error message the importer gives today for a bad key.
- [ ] **Step 2:** Move the code, replace `QaTopic` with `String` at the core boundary, and map at reading-tuvi's edge.
- [ ] **Step 3:** Run both modules' verify, including the moved ITs (`JdbcQaRepositoryIT`, `JdbcLlmUsageIT`, `JsonlKnowledgeImporterIT`) and `JdbcNumberUsageIT` / `NumberCheckServiceTest` → PASS. The Task 1 tests are unchanged.
- [ ] **Step 4:** Commit `refactor(reading): Q&A plumbing and knowledge import into reading-core`.

### Task 5: Generic Q&A pipeline + tử vi plugin

**Files:**
- Create:
  - `core.application.site.QaSitePlugin` (Global Constraints);
  - `core.application.qa.QaPipeline`: the generic part of today's `QaService`, covering quota, consume/refund through `IdentityPort` with a string topic, store, cache, retry hand-off, streaming sentinel handling and PII scrubbing before any plugin call that sends text out;
  - a core test `QaPipelineTest` with a fake plugin.
- Modify:
  - reading-tuvi `QaService` becomes a thin tử vi façade, or is replaced by `TuviQaPlugin` plus wiring. `QaController` keeps its exact routes, behaviour and responses.
  - `IdentityPort` moves to core with `consume(UUID accountId, UUID readingId, String topicKey)`; the profile lookup returns a site-specific birth via the plugin.

- [ ] **Step 1:** Write the failing `QaPipelineTest`:
  - (a) guard refusal → no consume, no LLM call;
  - (b) `prepare` throws upstream-unavailable → no consume;
  - (c) success → consume exactly once, before the prompt port is called;
  - (d) LLM failure after retries → refund once;
  - (e) a question containing `a@b.vn` and `0912 345 678` → the prompt port receives it scrubbed;
  - (f) an answer rejected by `filter` → stored as the safe fallback, the same as today's tử vi behaviour for filtered answers;
  - (g) free quota exhausted → the existing quota error.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement the split. Keep each existing `QaServiceTest` case. Change only how it constructs the subject, never what it asserts.
- [ ] **Step 4:** Run both modules' verify → PASS. Task 1 prompt fixtures unchanged, `QaControllerTest` and `ContractTest` unchanged.
- [ ] **Step 5:** Commit `refactor(reading): generic Q&A pipeline with a per-site plugin`.

### Task 6: Verify end to end, docs

**Files:**
- Modify: `CLAUDE.md` (repo map: `libs/{java/{platform-starter, reading-core}, rust/platform-core}`; one line under Java conventions that a reading service implements `KnowledgeKeyPolicy` + `QaSitePlugin` and wires core beans itself).
- Decide the `docs/system/index.html` change: add a "Lịch sử" line, or put `docs-system: n/a <reason>` in the PR body if no watched path changed. Run `bash scripts/check-system-docs.sh` to decide.

- [ ] **Step 1:** Bring up the stack the Q&A smoke needs, with the command from CLAUDE.md `scripts/smoke-qa.sh`. Run `scripts/smoke-reading.sh` and `scripts/smoke-qa.sh` → OK, then stop only those containers. If memory does not allow it, say so and rely on CI kind e2e (`smoke-reading`, `smoke-qa`).
- [ ] **Step 2:** Run `mvn -q -pl libs/java/reading-core,services/reading-tuvi -am verify` → PASS, with JaCoCo ≥ 80% for core `domain` + `application`.
- [ ] **Step 3:** Commit `docs: reading-core in the repo map`. The PR is opened by the controller after the final review (Fable 5.2, medium). The main session lands it.

# Platform Foundation — Design

- Ngày: 2026-11-23
- Trạng thái: Draft, chờ duyệt
- Người tham gia: Tuấn (owner), Claude (builder)

## 1. Mục đích & lý do

Dựng **bộ khung (platform) phát triển phần mềm** cho team 2 thành viên: Tuấn và Claude. Tuấn đưa yêu cầu, còn Claude làm trọn vòng: làm rõ → plan → TDD → code → review → build → push → CI/CD → deploy → monitor → rút kinh nghiệm.

Hiện **chưa có bài toán nghiệp vụ cụ thể**. Mục tiêu là có khung chuẩn ngay từ đầu, để khi có bài toán thì chỉ cần thêm service, không phải dựng lại hạ tầng.

Các quyết định Tuấn đã chốt:
- Kiến trúc **microservices ngay từ đầu**, **đa ngôn ngữ** (Java/Spring Boot + Rust).
- CI/CD bằng **GitHub Actions**, repo **public**, dùng gói free.
- Hạ tầng theo **phương án A** (một VM tự quản, monitor tự host). Ban đầu nhắm Oracle Always Free nhưng không đăng ký được; nơi chạy cụ thể xem §16.
- Domain mua sau, chuyển sang phải dễ.

## 2. Goals / Non-goals / Anti-goals

**Goals**
- G1. Tạo được service mới (Java hoặc Rust) từ template bằng một lệnh. Service mới có sẵn: health, metric, log, trace, Dockerfile, CI, manifest deploy.
- G2. Mỗi commit lên `main` tự động đi hết chuỗi: build → test → scan → image → deploy → smoke test. Lỗi thì tự rollback.
- G3. Quan sát được toàn hệ thống: theo một request xuyên các service bằng `trace_id`, có dashboard RED cho từng service, có cảnh báo.
- G4. Chuyển server hoặc domain chỉ bằng cấu hình và script bootstrap, không sửa code.
- G5. Có vòng học từ lỗi: mỗi lần CI fail, bug hay incident đều để lại bài học; lỗi lặp lại thì thành quy tắc hoặc check tự động.
- G6. Có walking skeleton gồm 2 service demo (1 Java, 1 Rust) để chứng minh khung chạy đầu-cuối.

**Non-goals (giai đoạn này)**
- Chưa có nghiệp vụ thật, chưa có frontend.
- Chưa multi-node HA, chưa multi-region.
- Chưa có identity provider đầy đủ (Keycloak…): chỉ chừa sẵn chỗ (xem §11).
- Chưa có môi trường staging riêng: VM free chỉ có một (xem §11).

**Anti-goals (coi là thất bại dù vẫn "chạy")**
- Khung phức tạp đến mức thêm một service mất hơn 1 giờ.
- Template Java và Rust lệch chuẩn nhau: khác cách log, khác format lỗi, khác cách health check.
- Deploy cần thao tác tay trên server.
- Gặp lỗi thì không trace được nguyên nhân (thiếu `trace_id` trong log, thiếu metric).
- Gắn chặt vào Oracle hoặc Cloudflare đến mức không rời đi được.

## 3. Ràng buộc

- Chi phí bằng 0 ở giai đoạn đầu.
- Server là **ARM64**, nên mọi image phải build cho `linux/arm64`, và `linux/amd64` nếu cần chạy local trên máy x86.
- Máy dev là Windows 10, đã có Java 21, Maven 3.9, Docker, Git, gh. **Còn thiếu Rust toolchain** (sẽ cài qua rustup).
- RAM 24GB dùng chung cho cả platform lẫn service, nên phải ưu tiên thành phần nhẹ.
- Oracle có thể thu hồi VM Always Free nếu VM để không quá lâu. Có sẵn script bootstrap thì dựng lại nhanh.

## 4. Approaches đã cân nhắc

| Phương án | Ưu | Nhược | Kết quả |
|---|---|---|---|
| A. Oracle VM + tự host monitor | Không bị cold start, nhiều RAM, kiểm soát toàn bộ, gần môi trường production thật | Phải tự vận hành VM | **Chọn** |
| B. PaaS free (Render/Koyeb) | Không phải vận hành | Cold start 30 giây trở lên, ~512MB RAM, 1 service, không chạy được microservices | Loại |
| C. Oracle chạy app + Grafana Cloud free | Không phải chạy stack monitor | Giới hạn 10k series, dữ liệu giữ 14 ngày | Để dành: nhờ OpenTelemetry, chuyển sang được bằng một dòng cấu hình |

Về **runtime trên VM**, Claude chọn như sau (Tuấn sửa nếu không đồng ý):

| Lựa chọn | Lý do |
|---|---|
| Docker Compose | Đơn giản, nhưng không "chuẩn từ đầu" như Tuấn muốn: không có self-healing, rolling update hay GitOps; khi lên cloud lớn thì phải viết lại |
| **k3s (Kubernetes nhẹ) + Argo CD (GitOps)** — **chọn** | Đúng chuẩn Kubernetes: chuyển sang EKS/GKE/AKS hay một cluster nhiều node mà manifest giữ nguyên. Argo CD kéo cấu hình từ Git nên server không cần mở cổng cho CI đẩy vào; rollback chỉ là `git revert`. Chi phí khoảng 2GB RAM, chấp nhận được |

## 5. Kiến trúc tổng thể

```
Internet ──► Cloudflare (DNS + Tunnel, HTTPS) ──► cloudflared (pod trong k3s)
                                                     │
                                                     ▼
                                       Traefik Ingress (có sẵn trong k3s)
                                       = API Gateway: routing, rate-limit, CORS
                         ┌───────────────────┬──────┴─────────────┐
                         ▼                   ▼                    ▼
                  order-svc (Java)    notify-svc (Rust)     <service mới…>
                         │   REST/OpenAPI    ▲
                         │                   │ consume
                         ▼   publish         │
                  Postgres (db/svc)  ──outbox──► Redpanda (Kafka API)
                         
  Mọi service ──OTLP──► OpenTelemetry Collector ──► Prometheus (metrics)
                                                ├──► Loki (logs)
                                                └──► Tempo (traces)
                                                        ▼
                                              Grafana (dashboard + alert)
```

### 5.1 Nguyên tắc service

- **Database per service**: dùng chung một Postgres instance, nhưng mỗi service có database và user riêng. Service này không đọc DB của service khác.
- **Giao tiếp đồng bộ**: REST, contract-first bằng OpenAPI đặt trong `contracts/`. Có thể bổ sung gRPC nội bộ sau.
- **Giao tiếp bất đồng bộ**: event qua **Redpanda**. Redpanda tương thích Kafka API nhưng nhẹ hơn; sau này đổi sang Kafka hoặc MSK mà không phải sửa code. Schema event đặt trong `contracts/events/` (JSON Schema, kèm tài liệu AsyncAPI).
- **Transactional outbox**: ghi event vào bảng outbox trong cùng transaction với dữ liệu, sau đó relay đẩy sang Redpanda. Consumer phải **idempotent** (lưu `event_id` đã xử lý).
- **Chuẩn chung cho mọi service, bất kể ngôn ngữ**:
  - Health: `/health/live` và `/health/ready`.
  - Metrics: xuất qua OTLP (Prometheus nhận qua Collector).
  - Log: JSON ra stdout, bắt buộc có các field `timestamp, level, service, trace_id, span_id, message`.
  - Lỗi HTTP: định dạng **RFC 9457 Problem Details**, thêm field `trace_id` để từ lỗi tra ngược ra trace.
  - Cấu hình: qua biến môi trường (12-factor). Secret không bao giờ nằm trong Git dưới dạng plaintext.
  - Graceful shutdown; image non-root, distroless/slim.

### 5.2 Template Java service

- Spring Boot bản stable mới nhất (dòng 4.x), Java 21 (dùng virtual threads), Maven.
- Package theo **hexagonal**: `domain` (thuần Java, không phụ thuộc framework), `application` (use case, port), `adapter.in.web`, `adapter.out.persistence`, `adapter.out.messaging`. Ranh giới kiểm tra bằng **ArchUnit**.
- Flyway cho migration; Spring Data JDBC hoặc JPA tùy service (mặc định JDBC cho đơn giản và dễ trace).
- Observability: OpenTelemetry Java agent + Micrometer; log bằng Logback JSON encoder.
- Test: JUnit 5 + AssertJ, **Testcontainers** (Postgres, Redpanda), kiểm tra contract với OpenAPI.
- Chất lượng code: Spotless (google-java-format), Error Prone hoặc SpotBugs, JaCoCo (ngưỡng coverage ở §7).
- Thư viện chung `libs/java/platform-starter`: auto-config cho observability, Problem Details, outbox, chuẩn log.

### 5.3 Template Rust service

- axum + tokio, sqlx (Postgres, có migration), rdkafka (Redpanda).
- Cấu trúc tương đương hexagonal: `domain/`, `application/`, `adapters/{http,db,messaging}/`.
- Observability: `tracing` + `tracing-opentelemetry` + `opentelemetry-otlp`; log JSON qua `tracing-subscriber`.
- Lỗi: `thiserror` cho domain, `anyhow` ở biên; map ra Problem Details.
- Test: `cargo test`, testcontainers-rs.
- Chất lượng code: `rustfmt`, `clippy -D warnings`, `cargo-deny` (license và lỗ hổng), `cargo-llvm-cov`.
- Crate chung `libs/rust/platform-core`: bộ khởi tạo telemetry, Problem Details, health, consumer idempotent.

### 5.4 Walking skeleton (demo)

- `order-svc` (Java): `POST /orders` lưu đơn và ghi event `OrderPlaced` vào outbox; `GET /orders/{id}`.
- `notify-svc` (Rust): consume `OrderPlaced`, ghi nhận (log), có endpoint `GET /notifications`.
- Tiêu chí xong: một request `POST /orders` tạo ra **một trace duy nhất** đi xuyên cả 2 service và nhìn thấy được trong Grafana Tempo; dashboard RED có số liệu; log của cả 2 service lọc được theo `trace_id`.

## 6. Cấu trúc repo (monorepo)

Chọn **monorepo** vì chỉ có 2 người làm. Một PR có thể đổi đồng thời contract và cả 2 service, CI chỉ chạy những phần bị ảnh hưởng nhờ lọc theo path, và Claude có toàn bộ ngữ cảnh ở một chỗ.

```
platform/
├── CLAUDE.md                     # luật làm việc + chuẩn code (Claude đọc mỗi phiên)
├── .claude/
│   ├── settings.json             # hooks, permissions
│   └── skills/                   # skill riêng của dự án (§8)
├── contracts/
│   ├── openapi/<svc>.yaml
│   └── events/<event>.schema.json
├── services/
│   ├── order-svc/                # Java
│   └── notify-svc/               # Rust
├── libs/
│   ├── java/platform-starter/
│   └── rust/platform-core/
├── templates/
│   ├── java-service/
│   └── rust-service/
├── deploy/
│   ├── local/docker-compose.yml  # hạ tầng phụ thuộc để dev ở máy
│   ├── k8s/
│   │   ├── base/<svc>/           # Kustomize base
│   │   ├── overlays/prod/        # image tag do CI cập nhật
│   │   └── platform/             # redpanda, postgres, otel, grafana stack, cloudflared
│   └── argocd/                   # App-of-apps
├── infra/
│   └── bootstrap/                # script dựng VM: k3s + argocd + sealed-secrets (idempotent)
├── scripts/                      # new-service, verify-deploy, ...
├── docs/
│   ├── adr/                      # Architecture Decision Records
│   ├── superpowers/specs|plans/
│   ├── runbooks/
│   └── lessons/                  # bài học từ lỗi (§9)
└── .github/
    ├── workflows/                # ci-java.yml, ci-rust.yml (reusable), cd.yml, ...
    └── dependabot.yml
```

## 7. CI/CD

**CI (mỗi PR và push)**, lọc theo path nên chỉ chạy job của service bị đổi:
1. Lint/format: Spotless (Java), rustfmt + clippy (Rust).
2. Unit test và integration test (Testcontainers); coverage gate ≥ 80% cho `domain` và `application`.
3. Kiểm tra contract: OpenAPI lint (Spectral); kiểm tra tương thích ngược của schema event.
4. Bảo mật: CodeQL, Trivy (image + IaC), `cargo-deny`, Dependabot.
5. Validate manifest: `kustomize build` + kubeconform.
6. Build image multi-arch bằng **runner ARM native của GitHub** (miễn phí cho repo public) và đẩy lên **GHCR** với tag `sha-<commit>`.

**CD (merge vào `main`)**:
1. CI xanh → workflow cập nhật image tag trong `deploy/k8s/overlays/prod`, commit kèm `[skip ci]`.
2. Argo CD trên VM thấy Git thay đổi → sync → rolling update.
3. Readiness probe bảo đảm pod mới chưa nhận traffic khi chưa sẵn sàng; nếu rollout fail, Kubernetes giữ nguyên bản cũ.
4. Workflow `verify-deploy` gọi smoke test qua URL công khai và kiểm tra trạng thái Argo CD. Fail thì `git revert` commit bump tag (tự động rollback) và mở issue.

**Branching**: trunk-based. Mỗi việc làm trên một branch ngắn (worktree) → PR → CI → squash merge. `main` được bảo vệ, bắt buộc CI xanh.

## 8. Quy trình làm việc tự động (Tuấn ↔ Claude)

```
Tuấn đưa yêu cầu
  → brainstorming        (làm rõ, hỏi lại; Tuấn duyệt spec)
  → writing-plans        (plan chia task nhỏ, mỗi task có test)
  → worktree/branch
  → TDD từng task        (test đỏ → code → xanh → refactor), dùng skill ngôn ngữ
  → code-review          (review tự động + subagent review)
  → verification         (chạy build/test local, có bằng chứng)
  → PR → CI → merge → CD → verify-deploy (smoke + truy vấn metric/trace)
  → retro                (nếu có fail ở bất kỳ bước nào → ghi lesson)
  → báo cáo Tuấn
```

**Các skill dự án** sẽ tạo trong `.claude/skills/`:
- `java-service-conventions`: chuẩn hexagonal, cách viết test, logging/error, checklist review cho Java.
- `rust-service-conventions`: tương tự cho Rust.
- `new-service`: scaffold một service từ template, đăng ký vào CI, Kustomize, Argo CD và contracts.
- `ship`: chuỗi PR → chờ CI → merge → theo dõi deploy → verify.
- `observe`: truy vấn Prometheus, Loki, Tempo để kiểm tra hoặc debug sau deploy.
- `retro`: ghi lesson và đề xuất nâng cấp quy tắc (§9).

**Hooks** (`.claude/settings.json`): tự format file sau khi sửa (Spotless/rustfmt); chặn commit secret; nhắc chạy test liên quan trước khi kết thúc lượt.

**Quyền truy cập của Claude**: `gh` (đã cài) để làm việc với repo, PR và CI; `kubectl`/`argocd` để kiểm tra trạng thái; Grafana API (token read-only) để đọc metric, log và trace.

**Mạng quản trị** (quyết định của Claude): dùng **Tailscale** (gói free) làm mạng riêng giữa VM, máy dev và GitHub runner (qua action `tailscale/github-action` với OAuth client tạo tag `tag:ci`). Kubernetes API, Argo CD UI/API, Grafana và SSH **chỉ mở trong tailnet**, không lộ ra internet và không phụ thuộc vào việc đã có domain. Cloudflare Tunnel chỉ dùng cho traffic công khai của app.

## 9. Vòng tự cải tiến (learning loop)

1. **Ghi nhận**: mỗi lần CI fail, test fail ngoài dự kiến, bug lọt lên prod hoặc deploy fail, Claude chạy `retro` và tạo `docs/lessons/YYYY-MM-DD-<slug>.md` gồm: triệu chứng, nguyên nhân gốc, cách sửa, cách phát hiện sớm hơn.
2. **Phân loại**: gắn tag (build, test, design, infra, process…).
3. **Nâng cấp** theo thang mạnh dần:
   - Lần 1: chỉ ghi lesson.
   - Lặp lại lần 2: thêm quy tắc vào `CLAUDE.md` hoặc vào skill conventions tương ứng.
   - Có thể tự động hóa: biến thành check cứng (lint rule, ArchUnit test, bước CI, hook).
4. **Định kỳ**: mỗi khi xong một feature lớn, rà `docs/lessons/` để gộp hoặc dọn, và cập nhật skill bằng `superpowers:writing-skills`.
5. Những gì thuộc về cách Tuấn muốn làm việc (không phải về code) thì ghi vào memory của Claude.

## 10. Observability chi tiết

- **Metrics**: RED (rate, errors, duration) cho HTTP và consumer; JVM/runtime; độ trễ outbox; consumer lag của Redpanda; tài nguyên node.
- **Logs**: thu qua Collector vào Loki; nhãn `service`, `env`, `level`.
- **Traces**: lấy mẫu 100% ở giai đoạn đầu, giảm khi traffic tăng; lan truyền context theo W3C traceparent qua cả HTTP header lẫn Kafka header.
- **Dashboard as code**: JSON đặt trong `deploy/k8s/platform/grafana/dashboards/`.
- **Alert** (Grafana Alerting): error rate > 5% trong 5 phút; p95 > 1 giây; pod restart loop; consumer lag tăng liên tục; disk > 80%; deploy fail.
- **Giám sát từ bên ngoài**: alert trong cluster không báo được khi chính VM chết. Vì vậy thêm một uptime check bên ngoài (Healthchecks.io gói free, VM gửi heartbeat mỗi 5 phút; mất heartbeat thì báo qua cùng kênh alert).
- **Retention**: metrics 15 ngày, logs và traces 7 ngày (điều chỉnh theo dung lượng đĩa thực tế).
- Endpoint OTLP chỉ là một biến cấu hình, nên chuyển sang Grafana Cloud (phương án C) không phải sửa code.

## 11. Bảo mật & secret

- Secret của CI nằm trong GitHub Actions secrets (SSH key, token).
- Secret trong cluster dùng **Sealed Secrets**: secret được mã hóa trước khi commit vào Git, và chỉ cluster giải mã được.
- Image chạy non-root, filesystem read-only khi có thể; Trivy chặn lỗ hổng mức CRITICAL.
- Không mở cổng nào trên VM ra internet (kể cả SSH: chỉ truy cập qua Tailscale). Traffic web đi qua Cloudflare Tunnel.
- Commit tự động của CD (bump tag, revert) dùng một **GitHub App** riêng của repo, được phép bypass branch protection chỉ cho thư mục `deploy/k8s/overlays/`.
- Auth API: giai đoạn đầu chưa có. Đã chừa middleware ở Traefik và filter trong `platform-starter`/`platform-core` để thêm JWT (OIDC) sau; khi đó sẽ có ADR riêng.

## 12. Domain & khả năng chuyển nhà

- Mọi hostname là biến của overlay (`APP_DOMAIN`).
- Cloudflare Tunnel: đổi domain thì chỉ đổi cấu hình tunnel và DNS, không đụng tới VM.
- Chuyển VM hoặc cloud: chạy `infra/bootstrap` trên máy mới, Argo CD tự kéo toàn bộ trạng thái từ Git; dữ liệu Postgres khôi phục từ backup.
- Backup: CronJob `pg_dump` hằng ngày, đẩy lên object storage free (Oracle Object Storage 20GB free; có thể đổi sang Cloudflare R2).

## 13. Ngân sách tài nguyên (ước lượng)

| Thành phần | RAM |
|---|---|
| k3s + Traefik + cloudflared | ~1 GB |
| Argo CD + Sealed Secrets | ~1 GB |
| Redpanda (1 broker) | ~1.5 GB |
| Postgres | ~1 GB |
| OTel Collector + Prometheus + Loki + Tempo + Grafana | ~3 GB |
| order-svc (Java) | ~0.5 GB |
| notify-svc (Rust) | ~0.05 GB |
| **Tổng** | **~8 GB / 24 GB**, còn ~15GB cho service mới |

## 14. Thứ tự triển khai (để viết plan)

1. Repo + CLAUDE.md + skills/hook cơ bản + CI khung.
2. Libs chung + template Java + `order-svc` (TDD), chạy local bằng compose.
3. Template Rust + `notify-svc`, event qua Redpanda, trace xuyên 2 service ở local.
4. Manifest Kustomize + stack observability + dashboards.
5. Bootstrap VM Oracle (k3s, Argo CD, Sealed Secrets, cloudflared) và CD qua GitOps.
6. Verify-deploy, alert, backup, retro skill, skill `new-service`.

## 15. Câu hỏi mở / phần để builder quyết

- Domain, auto-merge, VM, kênh alert: đã chốt ở §16.
- (Builder) Phiên bản cụ thể của từng thư viện: chọn bản stable mới nhất lúc triển khai.
- (Builder) Tên repo GitHub: tạm đặt `platform`.

## 16. Quyết định chờ Tuấn

Tuấn đã trả lời ngày 2026-11-23:

1. **Domain**: chưa mua. Tạm dùng **Cloudflare quick tunnel** (`*.trycloudflare.com`, URL ngẫu nhiên, đổi mỗi lần cloudflared khởi động lại). Hệ quả:
   - `verify-deploy` gọi smoke test qua **ingress nội bộ trong tailnet**, không phụ thuộc URL công khai.
   - URL công khai hiện tại được đọc từ log của cloudflared; script `scripts/public-url` in ra URL đó.
   - Khi mua domain: chuyển sang named tunnel và đặt `APP_DOMAIN`. Không sửa code.
2. **Tự động merge**: Claude **tự merge PR vào `main`** khi CI xanh, không cần Tuấn duyệt từng PR. Branch protection chỉ bắt buộc CI xanh, không bắt buộc review.
3. **VM**: Không đăng ký được Oracle Cloud vì Oracle yêu cầu thẻ vật lý, Tuấn chỉ có thẻ ảo. Máy dev yếu (i5-3337U 2 nhân, 12GB RAM) nên **không làm host**, và cũng **không chạy cả cluster ở local**. Cách làm thay thế:
   - **Local (nhẹ)**: chỉ chạy unit test và Testcontainers. `deploy/local/docker-compose.yml` bật theo nhu cầu, gồm Postgres + Redpanda + một container `grafana/otel-lgtm` (gói gọn cả bộ observability), tổng khoảng ≤ 3GB. Không dùng k3d ở local.
   - **Kubernetes thật chạy trong CI**: GitHub runner của repo public có 4 vCPU / 16GB. Mỗi lần merge vào `main`, CI dựng cluster tạm bằng **kind**, apply toàn bộ manifest (platform + services), chạy e2e test (POST order → kiểm tra notify nhận được event → kiểm tra trace có đủ 2 service), rồi xóa cluster. Cluster tạm này đóng vai trò "staging" cho đến khi có VM.
   - Hệ quả: phase 1–4 kiểm chứng toàn bộ trên CI; CD lên prod (Argo CD) bật khi có host.
   Nơi chạy prod quyết định trước phase 5, chọn một trong hai:
   - VPS trả phí rẻ, thanh toán được bằng thẻ ảo hoặc phương thức nội địa, tối thiểu 8GB RAM (16GB là thoải mái). Ví dụ Hetzner CAX21 ~€10.49/tháng, nhưng thường hết hàng.
   - Máy ở nhà chạy Ubuntu 24/7, đi qua Cloudflare Tunnel.
   `infra/bootstrap` phải chạy được trên **bất kỳ máy Ubuntu 24.04 nào, cả x86 lẫn ARM**, không phụ thuộc nhà cung cấp; image build cả `amd64` lẫn `arm64`. Nếu VM chỉ có 8GB thì giảm retention và giới hạn RAM của stack observability.
4. **Alert**: qua **Telegram bot** (token bot + chat_id lưu trong Sealed Secret/GitHub secret).

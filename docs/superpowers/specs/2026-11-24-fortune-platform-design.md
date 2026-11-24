# Fortune Platform (Tử vi + Chiêm tinh AI) — Design

- Ngày: 2026-11-24 · Trạng thái: **Đã duyệt** (Tuấn, 2026-11-24) · Owner: Tuấn · Builder: Claude
- Nền tảng kỹ thuật: platform monorepo (`docs/superpowers/specs/2026-11-23-platform-foundation-design.md`) — Java platform-starter, Rust platform-core, outbox/Redpanda, OTel/LGTM, GitHub Actions, k3s+Argo CD (khi có host).

## 1. Mục đích & lý do

Bài toán nghiệp vụ đầu tiên chạy trên platform: **2 trang xem bói** — **Tử vi** (Tử Vi Đẩu Số, phương Đông) và **Chiêm tinh** (natal chart phương Tây) — mỗi trang **3 ngôn ngữ: Việt, Anh, Trung (phồn thể trước)**. Thị trường đã nhiều web tử vi/chiêm tinh và app "AI astrologer"; điểm yếu chung của họ: để LLM tự tính lá số và trộn trường phái → sai, mâu thuẫn. Khác biệt của mình:
1. **Lá số do engine tính chuẩn**, test bằng hàng nghìn ca đối chiếu; AI **không bao giờ tự tính**.
2. **AI chỉ luận dựa trên kho luận giải** (RAG) dựng từ kinh sách, nói tự nhiên, không bịa ngoài kho.
3. **Chi phí AI ≈ 0**: dùng LLM free tier, phần xem chính không cần LLM.

Chức năng chính là **xem bói**; chat AI là phụ trợ.

## 2. Goals / Non-goals / Anti-goals

**Goals**
- G1. Người dùng nhập ngày giờ sinh (+ giới tính cho tử vi, nơi sinh cho chiêm tinh) → nhận lá số đúng + luận giải cơ bản **miễn phí**, không cần đăng nhập.
- G2. **Hỏi theo chủ đề (trả phí theo lượt)**: người dùng chọn chủ đề + đặt câu hỏi về lá số của chính mình → AI luận giải **chỉ dựa trên kho**, giọng văn tự nhiên, theo ngôn ngữ người dùng. 1 lượt = 1 câu hỏi về 1 chủ đề (§13).
- G3. **Lượt luận giải**: nạp tiền → nhận lượt; bài luận sinh một lần, lưu trong lịch sử, xem lại không tốn lượt. (Thay cho "báo cáo trả phí" và quota chat/ngày — §13.)
- G3b. **Chia sẻ**: chia sẻ lá số + tóm tắt dạng thẻ ảnh/link (§13).
- G4. Thanh toán: VietQR (VN) và PayPal (quốc tế) qua một payment service dùng chung cho cả 2 trang.
- G5. Kho luận giải là tài sản riêng: có cấu trúc, đa ngôn ngữ, truy được nguồn nội bộ, mở rộng ngôn ngữ thứ 4 chỉ bằng dịch thêm.
- G6. SEO tốt (traffic chính đến từ tìm kiếm): trang render sẵn, URL theo ngôn ngữ, nội dung tĩnh (12 cung, 14 chính tinh, 12 cung hoàng đạo…).

**Non-goals (bản đầu)**: app mobile; bói bài Tarot/Kinh Dịch/thần số học; xem tuổi hợp đôi (để sau, dễ thêm vì có lá số 2 người); cộng đồng/bình luận; chữ Trung giản thể; Paddle (giai đoạn 2).

**Anti-goals**
- AI tự tính lá số, hoặc phát biểu điều không có trong kho (bịa).
- Trộn trường phái trong cùng một lá số/luận giải.
- Kho chứa nội dung sao chép từ web/sách còn bản quyền.
- Chi phí LLM tăng tuyến tính theo traffic của phần miễn phí.
- Lộ ngày giờ sinh/nội dung chat của người dùng.

## 3. Ràng buộc

- Chi phí ≈ 0 giai đoạn đầu: LLM free tier (Groq ~1.000 req/ngày/model; Gemini Flash-Lite ~500/ngày, Flash ~20/ngày — số liệu cộng đồng, thay đổi thường xuyên); không dùng Claude API.
- Host prod chưa có (xem spec nền §16); dev máy yếu → mọi thứ phải chạy được trên 1 VM 8–16GB.
- Stripe không hỗ trợ VN; Lemon Squeezy đóng cửa; Trung Quốc đại lục chặn web quốc tế → người dùng tiếng Trung chủ yếu ở Đài Loan/HK/SG/MY.
- Swiss Ephemeris là AGPL/thương mại; repo đang public nên AGPL khả thi nhưng ưu tiên engine MIT nếu đạt độ chính xác.

## 4. Approaches đã cân nhắc

| | Mô tả | Kết quả |
|---|---|---|
| **A** | Service theo năng lực: identity, payment (dùng chung), chart (Rust, thuần tính toán), reading (cùng code, deploy 2 bản với DB riêng: tuvi, astro), web Next.js | **Chọn** (Tuấn, 2026-11-24): đúng hướng microservice, không trùng code RAG, chart tách riêng để test độc lập |
| B | Mỗi trang một service lớn + payment chung | Loại: trùng RAG/LLM/quota, chart dính luận giải |
| C | Modular monolith cho xem bói + payment | Loại: ngược hướng microservice, mất x-iztro Rust |

## 5. Quyết định của Tuấn (giữ nguyên khi build)

1. 2 trang riêng (tử vi, chiêm tinh) × 3 ngôn ngữ (vi, en, zh). Dùng chung payment; khác nhau DB luận giải.
2. AI không tự tính lá số; AI dùng RAG trên dữ liệu có sẵn, trả lời tự nhiên.
3. LLM miễn phí (kiểu Groq/Gemini), không dùng Claude API.
4. Mô hình doanh thu: **free** (lá số + luận cơ bản) + **trả phí theo lượt luận giải** (sửa sau prototype v1, §13).
5. Lịch/trường phái chọn **theo IP**: IP Việt Nam → âm lịch Việt (UTC+7) + trường phái Việt; khác → lịch Trung Hoa (UTC+8) + trường phái Trung Hoa. Không cho người dùng chọn.
6. Nguồn: dựa vào **kinh sách** rồi luận ra; **không hiển thị trích nguồn** cho người dùng.
7. Thanh toán: SePay/VietQR (VN) + PayPal (quốc tế) trước, Paddle sau.
8. Kiến trúc A.

## 6. Kiến trúc

```
web (Next.js SSR, i18n vi/en/zh-Hant) — 2 site cùng codebase, cấu hình theo host
        │ REST (qua Traefik ingress)
 ┌──────┼──────────────┬─────────────────────┬─────────────────────────┐
identity-svc        chart-svc (Rust)     reading-tuvi / reading-astro   payment-svc (Java)
(Java)              stateless             (Java, cùng image, 2 deploy)   SePay, PayPal adapters
 accounts, guest     tuvi: x-iztro         DB riêng mỗi bản:              orders, webhooks
 sessions, quota     + lịch Việt           knowledge, chunks(pgvector),   ── outbox ──► PaymentSucceeded
                     astro: ephemeris      charts cache, chat, reports           │
                                           LLM gateway (Groq→Gemini)      Redpanda ◄┘
                                           ◄── consume PaymentSucceeded → generate report
```

### 6.1 web
- Next.js (App Router, SSR/ISR), next-intl; URL `/{lang}/...`; ngôn ngữ mặc định theo `Accept-Language`, đổi được.
- Một codebase, cấu hình site theo hostname (`tuvi.*`, `astro.*` hoặc 2 domain riêng khi mua) → theme, menu, loại form.
- Trang tĩnh SEO: giới thiệu 12 cung/14 chính tinh/12 cung hoàng đạo/hành tinh, sinh từ kho (đã duyệt) và build-time ISR.
- Lá số tử vi hiển thị dạng bàn 12 cung chuẩn (SVG); chiêm tinh dạng vòng tròn natal chart (SVG).
- Disclaimer "mang tính tham khảo/giải trí" ở footer và trang báo cáo.

### 6.2 identity-svc (Java)
- Mặc định **khách** (guest id trong cookie httpOnly) — xem lá số & chat quota khách không cần đăng nhập.
- Tài khoản khi mua báo cáo: **email magic-link** + **Google login** (OIDC). Không lưu mật khẩu. *(Claude's call)*
- ~~Quota chat/ngày~~ → thay bằng số dư lượt (`credit_balance`) của tài khoản (§13).
- Lưu "hồ sơ lá số" (tên gợi nhớ + dữ liệu sinh) cho tài khoản; dữ liệu sinh mã hóa at-rest.

### 6.3 chart-svc (Rust, không DB)
- `POST /tuvi/chart` {solar datetime, gender, calendar: VN|CN, timezone} → lá số: 12 cung (tên cung, can chi), chính tinh/phụ tinh với độ sáng, Tứ Hóa, cục, mệnh chủ/thân chủ, đại vận/tiểu vận; trường `school` ghi rõ phái.
  - Engine: **x-iztro** (Rust, port iztro, MIT). Bổ sung **chuyển đổi âm lịch Việt** (thuật toán Hồ Ngọc Đức, UTC+7) khi calendar=VN; lịch sử múi giờ VN (miền Nam UTC+8 giai đoạn 1945–1975) qua tz database theo nơi sinh.
  - Giờ Tý (23h–1h): quy ước tính sang ngày hôm sau, ghi trong `school`. *(Claude's call)*
- `POST /astro/chart` {datetime, lat, lon, house system (mặc định Placidus)} → 10 thiên thể + Node/Chiron (tùy chọn), cung, nhà, góc chiếu (aspects) với orb chuẩn, Asc/MC.
  - Engine: ưu tiên engine MIT (astronomy-engine hoặc tương đương) nếu sai số ≤ 1 phút góc so với Swiss Ephemeris trên bộ test; không đạt → Swiss Ephemeris dưới AGPL (repo public). *(để builder đo và chọn)*
- Thuần hàm: cùng input → cùng output; **bộ test đối chiếu** (golden tests): tử vi so với iztro gốc + một số lá số tham chiếu lịch Việt; chiêm tinh so với Swiss Ephemeris.
- Geocoding nơi sinh: dataset offline (GeoNames cities15000) trong web/identity, không gọi API ngoài. *(Claude's call)*

### 6.4 reading-svc (Java; 2 deployment: reading-tuvi, reading-astro)
**Kho luận giải (knowledge)** — mỗi bản ghi:
- `key` có cấu trúc (vd `tuvi:star=TuVi:palace=Menh:brightness=Mieu`, `tuvi:palace=PhuThe:tuhoa=Ky`, `astro:planet=Venus:sign=Taurus`, `astro:aspect=Sun-square-Moon`), `school`, `topic` (tính cách/tình duyên/sự nghiệp/tài lộc/sức khỏe/vận hạn).
- `content` theo ngôn ngữ {vi, en, zh-Hant}, `source_ref` (nội bộ, không hiển thị), `status` (draft/approved), `version`.
- `chunks` cho tìm kiếm ngữ nghĩa: embedding đa ngôn ngữ chạy tại chỗ (model nhỏ hỗ trợ vi/en/zh, ONNX trên CPU), lưu **pgvector**.

**Pipeline dựng kho (offline, batch, chạy dần theo quota free)**
1. Thu thập văn bản **phạm vi công cộng**: tử vi — kinh điển chữ Hán (Tử Vi Đẩu Số Toàn Thư, …) từ Wikisource (維基文庫); chiêm tinh — sách tiếng Anh cổ (Project Gutenberg, …). Ghi nguồn + giấy phép từng tài liệu.
2. Tách đoạn → LLM trích xuất thành bản ghi có `key` + `topic` (một trường phái mỗi kho).
3. Dịch/biên soạn sang 3 ngôn ngữ bằng LLM, văn phong hiện đại tự nhiên.
4. Kiểm tra tự động (khóa hợp lệ, không trùng, độ dài, ngôn ngữ đúng) → `approved`. Có trang admin tối giản để xem/sửa/ẩn bản ghi. *(không có người duyệt chuyên môn — quyết định 6)*

**Luận giải cơ bản (miễn phí, không LLM)**: từ lá số sinh danh sách `key` → lấy bản ghi approved → ghép theo khung trình bày cố định theo ngôn ngữ. Chi phí 0, cache theo `chart_hash`.

**Chat AI**: câu hỏi → (1) lấy bản ghi theo các `key` liên quan lá số + (2) top-k chunk ngữ nghĩa → prompt "chỉ dùng tư liệu dưới đây; không có thì nói không đủ dữ liệu; không tự tính lá số; trả lời bằng {lang}, giọng tự nhiên" → LLM. Lưu `used_knowledge_ids` nội bộ. Cache theo (chart_hash, câu hỏi chuẩn hóa, lang).

**Báo cáo trả phí**: khi nhận `PaymentSucceeded` → sinh theo template mục lục cố định (mỗi mục = tập `key` + 1 lời gọi LLM viết mượt) → lưu HTML + PDF → trạng thái `ready` → web hiển thị. Lỗi LLM/quota → hàng đợi retry, báo người dùng "đang soạn". Báo cáo vận hạn năm/tháng dùng thêm lá số lưu niên/lưu nguyệt từ chart-svc.

**LLM gateway** (thư viện trong reading-svc): API chuẩn OpenAI-compatible; thứ tự provider cấu hình được (mặc định Groq → Gemini Flash-Lite); theo dõi quota từng provider; hết quota → chuyển provider; hết tất cả → chat trả "hôm nay hết lượt", báo cáo xếp hàng chờ. API key chỉ qua secret.

### 6.5 payment-svc (Java)
- Đơn hàng: `order(id, account_id, product_code, chart_profile_id, amount, currency, provider, status)`; bảng giá theo sản phẩm × tiền tệ (VND, USD).
- **SePay/VietQR**: tạo mã QR với nội dung chuyển khoản chứa mã đơn; nhận webhook (xác thực chữ ký/HMAC) → khớp số tiền + mã đơn → `PAID`. Job đối soát định kỳ qua API giao dịch phòng mất webhook.
- **PayPal**: Orders API (checkout) + webhook `PAYMENT.CAPTURE.COMPLETED`.
- `PAID` → outbox `PaymentSucceeded{order_id, account_id, product_code, chart_profile_id, site}` → Redpanda.
- Idempotent với webhook lặp; không lưu dữ liệu thẻ.

### 6.6 Lịch/trường phái theo IP
- Edge (Traefik/Cloudflare) cung cấp quốc gia (header `CF-IPCountry` khi qua Cloudflare; fallback GeoLite2 offline). VN → `calendar=VN, school=vn`; khác → `CN, school=cn`. Gắn vào lá số khi tạo và **lưu cố định** cùng hồ sơ (không đổi khi người dùng đi nước khác).

## 7. Dữ liệu & quyền riêng tư
- Dữ liệu sinh + chat = dữ liệu cá nhân: mã hóa cột nhạy cảm, retention chat 90 ngày *(Claude's call)*, người dùng xóa được hồ sơ.
- LLM free tier có thể dùng dữ liệu gửi lên để huấn luyện → **không gửi tên/email**; chỉ gửi lá số đã ẩn danh + câu hỏi. Ghi rõ trong chính sách riêng tư.
- Log không chứa ngày giờ sinh đầy đủ.

## 8. Observability (theo platform)
- Metric nghiệp vụ: lượt lập lá số theo site/lang, tỉ lệ chat hết quota, quota còn lại từng LLM provider, thời gian sinh báo cáo, đơn theo trạng thái/provider, doanh thu theo ngày.
- Alert Telegram: provider LLM hết quota sớm (trước 18h), báo cáo kẹt > 30 phút, webhook thanh toán lỗi, đối soát lệch.

## 9. Lộ trình (đề xuất — Claude's call)
1. **Phase F1 — Tử vi, tiếng Việt, end-to-end**, chia 5 plan (mỗi plan một PR/loạt PR, test độc lập):
   - **F1.1** chart-svc (Rust): lịch âm Việt, an sao tử vi, lưu niên/lưu nguyệt/lưu nhật cho xem ngày, golden tests.
   - **F1.2** kho tử vi + reading-tuvi (Java): pipeline dựng kho (nguồn PD → trích xuất → biên soạn tiếng Việt → kiểm tra), luận cơ bản và "Hôm nay" không LLM, khung lời thoại Lão Thiên Cơ.
   - **F1.3** identity-svc + payment-svc (Java): khách/magic-link/Google, 1 hồ sơ/tài khoản + khóa, quyền lợi (lượt, thẻ tháng/năm), SePay webhook + đối soát + hàng chưa khớp.
   - **F1.4** LLM gateway + hỏi theo chủ đề: trừ/hoàn lượt, 2 câu nối tiếp, trần 30 câu/ngày, stream SSE, bộ lọc đầu ra (§15).
   - **F1.5** web tuvi/vi (Next.js): giao diện theo prototype v2, hiệu ứng lời phán, chia sẻ, trang kiến thức SEO, e2e local.
2. **F2 — Chiêm tinh tiếng Việt**: chart-svc astro + kho astro + reading-astro + web astro.
3. **F3 — Tiếng Anh + Trung phồn thể** cho cả 2 trang + PayPal.
4. **F4** — thêm loại báo cáo, Paddle, xem tuổi hợp.

## 10. Phần để builder quyết
- Phiên bản thư viện; model embedding cụ thể (đa ngôn ngữ, chạy CPU, ≤ ~500MB RAM); kích thước chunk, k.
- Template prompt chi tiết, khung trình bày luận giải cơ bản, mục lục từng loại báo cáo.
- ~~Giá báo cáo~~ → giá gói đã chốt ở §14 (chỉnh sau khi thử).
- Engine ephemeris (theo tiêu chí §6.3).

## 11. Quyết định bổ sung sau builder check (Claude's call trừ khi ghi khác)

- **Nguồn kho — luật cứng**: chỉ văn bản gốc thuộc phạm vi công cộng (Wikisource/維基文庫, Project Gutenberg, các bản in cổ hết hạn bản quyền); không dùng bản dịch/chú giải hiện đại. Mỗi tài liệu nguồn ghi `license_basis` (vd "tác giả mất > 70 năm", "PD-old"). Nội dung luận giải 3 ngôn ngữ là bản biên soạn mới của mình từ nguồn gốc.
- **Ngưỡng chất lượng kho (không có chuyên gia)**: (1) kiểm tra tự động như §6.4; (2) bộ **eval 150 câu hỏi** mỗi trang × ngôn ngữ, chấm bằng LLM-judge theo 3 tiêu chí: bám tư liệu (không bịa), đúng ngôn ngữ, không mâu thuẫn trường phái; (3) Tuấn đọc thử ngẫu nhiên 30 câu trả lời/ngôn ngữ trước khi mở bán. F1 mở bán khi eval bám tư liệu ≥ 95%.
- **Trường phái Việt**: F1 dùng luật an sao của iztro (phái Trung Hoa phổ biến, có Tuần/Triệt) + **lịch Việt** cho IP Việt Nam; khác biệt phái Việt (nếu có) ghi thành backlog, bổ sung dần. Bộ golden test lịch Việt: ~50 lá số đối chiếu chéo với 2 công cụ tử vi Việt độc lập phổ biến (chỉ lấy dữ kiện an sao, không lấy văn luận giải).
- **Khách → tài khoản**: hồ sơ lá số và lịch sử chat của khách được gộp vào tài khoản khi đăng nhập; báo cáo luôn thuộc tài khoản.
- **zh-Hant**: dùng từ ngữ Đài Loan.
- **Topology**: F1 kiểm chứng trên CI (kind) như platform; bản prod cần host ≥ 8GB với cấu hình rút gọn (1 bản reading, embedding model nhỏ), 16GB cho đủ cả 2 site.
- **LLM free tier**: builder kiểm tra điều khoản thương mại của từng provider trước khi bật; gateway hỗ trợ cả endpoint trả phí (cùng API) để chuyển nhanh.

## 12. Quyết định của Tuấn sau builder check (2026-11-24)

1. **Lịch/trường phái**: tự động theo IP, nhưng **giao diện tiếng Việt → mặc định lịch Việt** (kể cả IP nước ngoài); có nút "đổi lịch" trong trang hồ sơ lá số (không hiện ở form chính). Trường `calendar` của hồ sơ sửa được.
2. **Thanh toán & hoàn tiền**: SePay gắn tài khoản ngân hàng cá nhân của Tuấn; Tuấn tự đăng ký SePay + PayPal. Báo cáo lỗi sau khi trả tiền → tự retry trong 24h → vẫn lỗi thì **hoàn 100%** (VietQR: tạo yêu cầu hoàn tiền cho Tuấn chuyển tay + thông báo người dùng; PayPal: refund qua API).
3. **Ngân sách AI**: chấp nhận **trả phí nhỏ** khi free tier không đủ hoặc điều khoản free không cho thương mại. LLM gateway: provider free trước, provider trả phí (Groq paid / Gemini paid, cùng API) làm tầng dự phòng; ngưỡng chi tiêu tháng cấu hình được, alert Telegram khi chạm 80%.

## 13. Quyết định của Tuấn sau xem prototype v1 (2026-11-24)

1. **Giao diện theo chủ đề**, không giống web thường: trang tử vi mang không khí phương Đông (bàn lá số như bảng sơn mài, son đỏ, vàng lá, chữ Hán thư pháp làm điểm nhấn); trang chiêm tinh mang không khí bầu trời đêm (vòng hoàng đạo, sao, đồng thau). Prototype v2: `docs/superpowers/specs/assets/2026-11-24-fortune-prototype-v2.html`.
2. **Thu phí theo lượt luận giải**: nạp 1 lượt → hỏi 1 câu về 1 chủ đề. Chủ đề: tình duyên, sự nghiệp, tài lộc, sức khỏe, gia đạo, vận năm.
   - *Chốt (2026-11-24)*: gói lượt lẻ theo §14 (1 lượt / 5 lượt); tặng 1 lượt khi tạo tài khoản; 1 lượt = bài luận + **2 câu hỏi nối tiếp** cùng chủ đề; lượt không hết hạn.
   - *Chốt*: luận giải lỗi sau 24h retry → **hoàn lượt tự động**; lượt chưa dùng vẫn hoàn tiền được khi người dùng yêu cầu.
   - Ảnh hưởng kiến trúc: payment-svc bán gói lượt; `PaymentSucceeded` → identity cộng `credit_balance`; reading-svc trừ lượt khi bắt đầu luận, hoàn khi lỗi (sổ lượt idempotent theo `order_id` / `reading_id`).
3. **1 tài khoản = 1 người**: đúng một hồ sơ lá số mỗi tài khoản.
   - *Chốt*: ngày giờ sinh/giới tính **khóa sau khi dùng lượt đầu tiên** (chống xem hộ nhiều người); trước đó sửa được. Khách vẫn lập lá số + xem luận cơ bản không cần đăng nhập; mua lượt cần tài khoản.
4. **Có chia sẻ**: thẻ ảnh dọc (lá số thu nhỏ + 1 câu tóm tắt) + link công khai; kênh Zalo, Facebook, Messenger, sao chép link, tải ảnh.
   - *Chốt*: link công khai chỉ hiện lá số + luận cơ bản, **ẩn ngày dương lịch đầy đủ** (chỉ năm can chi + giờ địa chi); bài luận trả phí chỉ chia sẻ khi người dùng bấm chia sẻ từng bài; tắt link được.

## 14. Quyết định của Tuấn sau prototype v2 (2026-11-24)

1. **Gói = phương án 2 (thẻ thời hạn + lượt lẻ)**:
   - Miễn phí: lá số, luận cơ bản, **"Hôm nay" tóm tắt** (công việc, đi lại, tiền bạc, sức khỏe: mức thuận/bình/lưu ý + 1 câu).
   - **Thẻ tháng** (trả trước 30 ngày, 49.000đ): xem ngày chi tiết (giờ tốt, hướng xuất hành, nên làm/nên tránh, lịch 7 ngày) + 2 lượt hỏi/tháng (lượt kèm thẻ hết hạn theo thẻ). **Thẻ năm** (399.000đ): mở tất cả, hỏi không giới hạn (trần ngầm 30 câu/ngày) + bài vận năm.
   - **Lượt lẻ** (1 lượt 29.000đ, 5 lượt 119.000đ): không hết hạn.
   - VietQR không trừ tiền định kỳ → thẻ là trả trước, tự gia hạn bằng tay, nhắc trước 3 ngày; PayPal dùng subscription tự gia hạn.
   - Xem ngày **không dùng LLM**: tử vi từ lưu niên/lưu nguyệt/lưu nhật + can chi ngày so với lá số; chiêm tinh từ transit (chủ yếu Mặt Trăng) so với bản đồ gốc → tra kho theo `key` (`tuvi:daily:...`, `astro:transit:...`), ghép khung cố định. Kho cần thêm nhóm bản ghi "ngày". chart-svc thêm `POST /tuvi/daily` và `POST /astro/transits`.
2. **Nhân vật thầy bói**: mọi luận giải hiện dạng **hội thoại** với một nhân vật có hình minh họa.
   - Tử vi: ông lão/cao nhân phương Đông (tên **"Lão Thiên Cơ"**, xưng "ta", gọi người dùng là "con").
   - Chiêm tinh: một thực thể đại diện cho bầu trời (**"Người Giữ Sao"**: hình người khoác áo choàng sao, mặt là chòm sao; xưng "ta", gọi "bạn").
   - Giọng nhân vật là một lớp trình bày: luận cơ bản và xem ngày (không LLM) dùng khung câu viết sẵn theo giọng nhân vật; lượt hỏi (LLM) có chỉ dẫn giọng trong prompt. Nội dung vẫn chỉ từ kho.
   - Vật ngoại trang: Lão Thiên Cơ cầm cuộn lá số (命盤), đeo hồ lô; Người Giữ Sao cầm quả cầu thiên văn phát sáng có vòng quỹ đạo.
   - Hình chính thức: minh họa vẽ riêng (thuê họa sĩ hoặc tạo bằng công cụ AI có quyền thương mại), nhiều biểu cảm (chào, đang xem, phán). Prototype dùng SVG tạm.

3. **Thẻ năm mở tất cả, hỏi không giới hạn** (kích cầu lên gói năm). **Tuấn đồng ý**: trần chống lạm dụng ngầm 30 câu/ngày/tài khoản (người dùng thường không chạm), vượt trần → "mai hỏi tiếp"; theo dõi chi phí LLM/thẻ năm, alert khi trung bình vượt ngưỡng.
4. **Lời phán động**: câu trả lời hiện dần (stream từ LLM; phần không LLM thì mô phỏng nhịp), hiệu ứng theo chủ đề — tử vi: chữ loang như mực, cuối bài đóng ấn son; chiêm tinh: chữ sáng lên như bụi sao, sao lấp lánh quanh lời nói. Tôn trọng `prefers-reduced-motion`; tự cuộn theo lời đang hiện; có nút hiện hết ngay.
5. **Kiểu chữ tiêu đề (Tuấn chọn B + C)**: tử vi **Charm** (nét bút lông), chiêm tinh **Great Vibes** (bay bổng) cho tiêu đề, tên nhân vật, thương hiệu; nhãn nhỏ dùng chữ có chân thường (Noto Serif Display / Cormorant). **Kiểu chữ chiêm tinh** (sau góp ý "chưa rõ ràng"): lời Người Giữ Sao nằm trong khung kính đêm viền đồng, chữ Cormorant Garamond 18–19px màu ánh sao; chữ thường dùng Spectral; ô nhập chỉ có gạch chân đồng. Tử vi giữ giấy dó + Noto Serif.

### Thanh toán SePay — cách cộng lượt
- Tạo đơn: payment-svc sinh mã đơn ngắn duy nhất (vd `TV8K2Q`) + số tiền → hiện VietQR (số tài khoản, số tiền, nội dung = mã đơn). Đơn chờ 30 phút nhưng tiền đến muộn vẫn khớp theo mã.
- Ngân hàng báo giao dịch → SePay gọi **webhook** của payment-svc (xác thực API key/chữ ký) với số tiền, nội dung, mã giao dịch ngân hàng → tách mã đơn từ nội dung → khớp đơn + số tiền → `PAID` → outbox `PaymentSucceeded` → identity cộng lượt / gia hạn thẻ → web cập nhật trong vài giây (SSE/poll).
- Idempotent theo mã giao dịch SePay. **Đối soát** mỗi 5 phút qua API SePay để bắt webhook bị lỡ.
- Ngoại lệ vào hàng **"chưa khớp"** (sai/thiếu nội dung, sai số tiền): trang admin cho Tuấn gán tay hoặc hoàn; chuyển thiếu → không cộng, nhắn người dùng; chuyển thừa → cộng gói đúng + ghi chú.
- Rủi ro pháp lý tổng hợp: xem §15.
- Tài khoản cá nhân: SePay hỗ trợ. Rủi ro là **thuế/pháp lý**, không phải kỹ thuật: tiền bán hàng vào tài khoản cá nhân vẫn là doanh thu phải kê khai; Tuấn nên dùng **một tài khoản riêng** chỉ cho web này và hỏi kế toán về đăng ký hộ kinh doanh trước khi mở bán.

## 15. Rủi ro pháp lý (Claude tổng hợp 2026-11-24 — không phải tư vấn luật; Tuấn cần luật sư/kế toán xác nhận trước khi mở bán)

| Rủi ro | Mức | Giảm thiểu (đưa vào sản phẩm) |
|---|---|---|
| **Mê tín dị đoan**: NĐ 38/2021 Đ.14 phạt hành chính hoạt động mê tín dị đoan (nguồn thứ cấp nêu 15–20 triệu + tịch thu lợi bất hợp pháp); BLHS Đ.320 "hành nghề mê tín, dị đoan" (hình sự khi gây hậu quả nghiêm trọng/thu lợi lớn) | Cao nhất | Định vị **văn hóa – tham khảo – giải trí** dựa trên sách cổ; disclaimer mọi trang luận; **cấm** bán giải hạn/cúng/bùa, cấm hù dọa ("hạn nặng, nạp tiền để giải"), cấm cam kết kết quả; không khuyên bỏ chữa bệnh, không khuyên quyết định tài chính/pháp lý cụ thể — prompt + bộ lọc đầu ra chặn các ý này |
| **Dữ liệu cá nhân**: Luật BVDLCN 91/2025/QH15 (hiệu lực 1/1/2026) + NĐ 356/2025: đồng ý rõ ràng, chính sách riêng tư, quyền xóa, **hồ sơ đánh giá tác động** khi xử lý và khi **chuyển dữ liệu ra nước ngoài** (gửi lá số/câu hỏi sang Groq/Gemini là chuyển xuyên biên giới) | Cao | Ẩn danh trước khi gửi LLM (không tên/email/ngày sinh dương lịch đầy đủ); màn đồng ý riêng; lập hồ sơ DPIA + hồ sơ chuyển dữ liệu ra nước ngoài; trang xóa dữ liệu (đã có) |
| **Thương mại điện tử**: web bán dịch vụ có thanh toán phải thông báo website TMĐT với Bộ Công Thương (online.gov.vn); điều kiện chủ thể (cá nhân/hộ KD) cần xác nhận | Trung bình | Đăng ký hộ kinh doanh trước, rồi thông báo website; hiển thị thông tin người bán, điều khoản, chính sách hoàn tiền |
| **Thuế**: doanh thu vào tài khoản cá nhân vẫn phải kê khai | Trung bình | Tài khoản riêng cho web; hỏi kế toán; xuất hóa đơn điện tử nếu thuộc diện |
| **Bảo vệ người tiêu dùng**: giá minh bạch, điều kiện gia hạn/hoàn tiền rõ, hợp đồng theo mẫu | Trung bình | Trang điều khoản; thẻ trả trước không tự trừ tiền; nhắc gia hạn; lịch sử giao dịch |
| **Cổng thanh toán quốc tế**: Braintree (thuộc PayPal) liệt kê "fortune tellers" là ngành bị hạn chế; PayPal AUP không nói rõ → nguy cơ **khóa tài khoản, giữ tiền** | Cao với thị trường quốc tế | Hỏi PayPal xác nhận bằng văn bản trước F3; mô tả là "nội dung văn hóa/giải trí"; chuẩn bị phương án thay thế |
| **Người chưa thành niên** | Thấp–TB | Xác nhận đủ 18 tuổi khi nạp tiền |
| **Nội dung do AI tạo**: quy định mới về công nghiệp công nghệ số/AI có thể yêu cầu ghi nhãn | Thấp–TB | Ghi rõ "nội dung do AI biên soạn từ kho tư liệu" |
| **Bản quyền**: kho (đã có luật cứng §11), hình nhân vật, font | Thấp | Họa sĩ chuyển nhượng quyền bằng hợp đồng; font OFL; tên thương hiệu tra trùng nhãn hiệu trước khi mua domain |

## 16. Quảng cáo (Tuấn đồng ý 2026-11-24)

- **Ra mắt không có quảng cáo bên ngoài.** Lý do: doanh thu quảng cáo VN thấp lúc traffic nhỏ; phá không khí sản phẩm và giảm chuyển đổi trả phí; mạng quảng cáo dễ hiện quảng cáo giải hạn/tâm linh/cờ bạc (đẩy về phía "mê tín dị đoan", §15); thêm nghĩa vụ đồng ý cookie theo luật dữ liệu.
- **Có ngay: quảng cáo nội bộ** (house ads): màn Hôm nay mời thẻ tháng/năm; trang kiến thức SEO mời "Lập lá số miễn phí".
- **Giai đoạn sau** (khi trang kiến thức đạt ~50.000 lượt xem/tháng): quảng cáo hiển thị **chỉ trên trang kiến thức** (12 cung, chính tinh, cung hoàng đạo…), **không bao giờ** trên lá số, bài luận, hội thoại, thanh toán; chặn nhóm tâm linh/giải hạn/cờ bạc/vay tiền; người có thẻ tháng/năm không thấy quảng cáo; banner đồng ý cookie riêng cho quảng cáo.
- Backlog: "xem quảng cáo nhận thêm 1 câu hỏi" cho người dùng miễn phí — xét sau.

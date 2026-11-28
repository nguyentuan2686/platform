# Xem số hợp mệnh (SIM, số tài khoản, biển số xe) — Design

- Ngày: 2026-11-28 · Trạng thái: **Chờ duyệt** · Owner: Tuấn · Builder: Claude
- Nằm trên: `2026-11-24-fortune-platform-design.md` (web tử vi Lão Thiên Cơ), service `reading-tuvi` + `web`.

## 1. Mục đích & lý do

Công cụ **miễn phí**: người dùng nhập một dãy số (SIM, số tài khoản ngân hàng, biển số xe) và năm sinh + giới tính, nhận ngay **điểm hợp mệnh (thang 10), các tiêu chí chấm và lời luận giải** theo giọng Lão Thiên Cơ.

Lý do: các trang đối thủ (xemboionline.vn, tuvi.vn) đều có công cụ này miễn phí và người Việt tìm kiếm rất nhiều ("sim hợp mệnh", "biển số hợp tuổi", "số tài khoản phong thủy"). Công cụ kéo người dùng từ Google vào, rồi dẫn sang xem lá số / hỏi thầy. (Lý do kéo traffic là suy luận của Claude, Tuấn chưa phản đối.)

## 2. Goals / Non-goals / Anti-goals

**Goals** (Tuấn đã chốt)
- G1. Miễn phí, không cần đăng nhập.
- G2. Kết quả do **máy tính** (code tất định, có test), không dùng AI/LLM: chấm điểm + luận giải ghép từ nội dung viết sẵn.
- G3. **Không lưu dãy số**: không ghi DB, không log, không đưa vào URL, không cache dưới dạng đọc được.
- G4. Giới hạn **2 lượt/người/ngày** (ngày giờ Việt Nam); xem lại đúng dãy số đã xem trong ngày **không trừ lượt**. Người = phiên khách/tài khoản (cookie) + kiểm tra thêm theo IP.
- G5. Ba loại số: SIM, số tài khoản, biển số xe. Đầu vào: năm sinh + giới tính (không cần ngày giờ sinh).

**Non-goals**
- Gợi ý/bán số đẹp, xếp hạng kho SIM, liên kết nhà mạng hay đại lý biển số.
- Luận bằng AI; nút "Hỏi thầy về số này" (để sau, cách C trong thảo luận).
- Bản tiếng Anh/Trung (sau, theo lộ trình đa ngôn ngữ chung).
- Đổi bộ lọc câu hỏi của phần Hỏi thầy: "số đẹp hôm nay" vẫn bị từ chối là cờ bạc.

**Anti-goals**
- Đưa ra con số để đánh lô đề, hay ám chỉ dãy số "chắc chắn" mang lại tiền/tai họa.
- Lời lẽ gây sợ, xúi đổi số/mua số ("kẻo mất", "nên đổi ngay").
- Lộ dãy số của người dùng ra log, DB, URL, analytics hoặc bên thứ ba.

## 3. Các cách đã cân nhắc

| Cách | Đánh đổi | Kết quả |
|---|---|---|
| A. Tính trong trình duyệt, nội dung viết sẵn | Riêng tư tuyệt đối, nhưng giới hạn lượt vô nghĩa (code nằm ở client) | Không chọn: Tuấn muốn giới hạn 2 lượt |
| B. Máy chủ tính, AI viết luận giải | Lời văn tự nhiên, nhưng tốn AI, dãy số dễ lọt ra nhà cung cấp AI, AI tính sai | Không chọn: Tuấn bỏ AI sau khi chốt "để máy tính" |
| **C. Máy chủ tính + chấm điểm, luận giải ghép từ kho viết sẵn** | Chính xác, tức thì, không tốn tiền, giới hạn lượt có nghĩa; lời văn kém tự nhiên hơn AI | **Chọn** |

## 4. Trải nghiệm người dùng

Trang (indexable, SEO):
- `/vi/xem-so` — trang tổng: 3 thẻ (SIM / Số tài khoản / Biển số xe), đoạn giới thiệu ngắn về cách xem.
- `/vi/xem-so/sim`, `/vi/xem-so/so-tai-khoan`, `/vi/xem-so/bien-so-xe` — mỗi trang một form + phần giải thích phương pháp tĩnh (cho SEO).

Form:
- Dãy số (ô nhập, chỉ nhận chữ số; biển số nhận cả chữ, dấu `-` `.`).
- Năm sinh dương lịch (1930…năm hiện tại) + ô tích "Sinh trước Tết Nguyên đán năm đó" (khi tích: năm âm = năm − 1). Giới tính Nam/Nữ.
- Trang số tài khoản có dòng: "Chỉ nhập số tài khoản. Không bao giờ nhập mật khẩu, mã OTP hay số thẻ."
- Dòng nhỏ: "Ta không lưu dãy số của con. Con được xem 2 số mỗi ngày."

```
┌─────────────────────────────────────┐
│  Điểm hợp mệnh: 9.5 / 10  (Rất hợp)  │
│  Mệnh con: Lộ Bàng Thổ · Cung phi Khảm│
├─────────────────────────────────────┤
│ Ngũ hành        Hỏa sinh Thổ  3.0/3  │
│ Quẻ Kinh Dịch   Địa Thiên Thái 2.5/2.5│
│ Âm dương        5 chẵn / 5 lẻ 1.5/1.5│
│ Cung phi        Hợp 1 quái   0.75/1.5│
│ Số nút          9 nút         0.5/0.5│
│ Cặp số dân gian 68, 39        1.0/1  │
(minh họa bố cục; số liệu thật lấy từ golden test)
├─────────────────────────────────────┤
│ Lời thầy: (3–6 đoạn ghép từ kho)     │
│ [Xem lá số tử vi của con →]          │
└─────────────────────────────────────┘
```

- Hết lượt: "Hôm nay con xem đủ 2 số rồi, mai quay lại với ta nhé." + nút xem lá số.
- Có link từ trang chủ `/vi` và footer.
- Kết quả hiển thị trên trang, không tạo URL kết quả, không chia sẻ được (dãy số là dữ liệu riêng).

## 5. Thuật toán (domain, thuần Java, tất định)

Tất cả trong `reading-tuvi` package `domain.number` (không Spring). Mọi bảng tra là hằng số trong code, có test.

### 5.1 Chuẩn hóa đầu vào
- SIM: bỏ khoảng trắng/dấu; `+84` → `0`; phải đúng 10 chữ số bắt đầu `0`. Dãy dùng để tính: cả 10 chữ số.
- Số tài khoản: 6–19 chữ số. Dãy dùng để tính: toàn bộ.
- Biển số: form có chọn **Ô tô / Xe máy** (vì `30A12345` đọc được hai cách). Chuẩn hóa chữ hoa, bỏ ký tự khác chữ-số.
  - Ô tô: `^\d{2}[A-Z]{1,2}\d{4,5}$` (ví dụ `30A12345`, `29LD1234`).
  - Xe máy: `^\d{2}[A-Z]{1,2}\d\d{4,5}$` (ví dụ `29B112345`, `59X31234`): chữ số ngay sau chữ cái thuộc sê-ri.
  - Dãy dùng để tính: **số thứ tự** (4–5 chữ số cuối). Mã tỉnh và sê-ri không tính.
- Sai định dạng → 400 `invalid_number`, không trừ lượt.

### 5.2 Mệnh và cung phi
- Năm âm `Y` (sau điều chỉnh Tết). Can chi theo vòng 60 Giáp Tý; **mệnh nạp âm** tra bảng 30 cặp (ví dụ 1984 Giáp Tý Hải Trung Kim, 1990 Canh Ngọ Lộ Bàng Thổ). Hành của mệnh = hành của nạp âm.
- Mốc đổi năm là **Tết Nguyên đán** (không dùng Lập Xuân); mọi phép tính dưới đây dùng năm âm `Y`. Ví dụ sinh tháng 1/2000 trước Tết là Kỷ Mão 1999.
- **Cung phi** (Bát trạch): `r` = tổng các chữ số của 2 chữ số cuối của `Y`, rút về 1 chữ số.
  - Năm < 2000: nam `10 − r`, nữ `5 + r` (rút về 1 chữ số).
  - Năm ≥ 2000: nam `9 − r` (0 → 9), nữ `6 + r` (rút về 1 chữ số).
  - Kết quả 5: nam → 2 (Khôn), nữ → 8 (Cấn).
  - 1 Khảm, 2 Khôn, 3 Chấn, 4 Tốn, 6 Càn, 7 Đoài, 8 Cấn, 9 Ly.
  - Đông tứ mệnh: Khảm, Chấn, Tốn, Ly. Tây tứ mệnh: Càn, Khôn, Cấn, Đoài.
- Test bắt buộc: 1990 nam Khảm, 1990 nữ Cấn, 2000 nam Ly, 2000 nữ Càn.

### 5.3 Sáu tiêu chí và điểm (tổng 10)

| Tiêu chí | Điểm tối đa | Cách tính |
|---|---|---|
| Ngũ hành | 3.0 | Mỗi chữ số mang một hành theo cách các trang xem SIM Việt Nam hay dùng: 0,1 Thủy; 2,5,8 Thổ; 3,4 Mộc; 6,7 Kim; 9 Hỏa. Hành của dãy số = hành có **nhiều chữ số nhất** (đếm số lượng, không cộng giá trị); hòa thì lấy hành của chữ số cuối trong các hành hòa. So với hành mệnh: số sinh mệnh 3.0; cùng hành 2.5; mệnh khắc số 1.5; mệnh sinh số 1.0; số khắc mệnh 0 |
| Quẻ Kinh Dịch | 2.5 | Chia dãy làm hai nửa (lẻ thì nửa đầu ít hơn, như Mai Hoa). Thượng quái = tổng nửa đầu mod 8, hạ quái = tổng nửa sau mod 8; với **cả hai**, 0 → 8. Tiên thiên: 1 Càn, 2 Đoài, 3 Ly, 4 Chấn, 5 Tốn, 6 Khảm, 7 Cấn, 8 Khôn. Tra bảng 64 quẻ (tên Việt + cát/bình/hung): cát 2.5, bình 1.25, hung 0. Không dùng hào động, quẻ hỗ, quẻ biến (bản đầu) |
| Âm dương | 1.5 | Chẵn = âm, lẻ = dương. Lệch `|chẵn − lẻ| ≤ 20%` độ dài (làm tròn lên) → 1.5; ≤ 40% → 0.75; còn lại 0 |
| Cung phi | 1.5 | Thượng quái và hạ quái cùng nhóm (Đông/Tây tứ) với cung phi người dùng: cả hai 1.5, một 0.75, không 0 |
| Số nút | 0.5 | Nút = tổng chữ số mod 10 (tổng tận cùng 0 là "0 nút", dân gian coi là bù, không tốt). 9 nút → 0.5; 7, 8 nút → 0.25; còn lại (kể cả 0 nút) 0 |
| Cặp số dân gian | 1.0 | Quét các cặp/bộ liền nhau trong dãy theo bảng (tốt: 68, 86, 39, 79, 38, 66, 88, 99, 6789…; kiêng: 49, 53…). Bắt đầu 0.5, mỗi bộ tốt +0.25, mỗi bộ kiêng −0.25, kẹp trong [0, 1.0] |

- Tổng làm tròn tới 0.5 gần nhất (đúng giữa thì làm tròn lên, ví dụ 9.25 → 9.5). Nhóm: ≥ 8 "Rất hợp", 6.5–7.5 "Khá hợp", 5–6 "Bình thường", < 5 "Chưa hợp lắm".
- Âm dương với dãy lẻ chữ số: không thể chia đều, lệch 1 chữ số được tính là cân bằng (đúng ý).
- Thứ tự "mệnh khắc số" (1.5) trên "mệnh sinh số" (1.0): mệnh vẫn làm chủ được số, còn sinh xuất là hao mệnh. Các trang khác nhau chỗ này; đây là quyết định của Claude.
- Các trọng số, ngưỡng và bảng cặp số là **quyết định của Claude** (tổng hợp từ các cách xem phổ biến), nằm trong một chỗ để chỉnh sau; thay đổi là thay đổi code có test.

## 6. Luận giải

- Nội dung viết sẵn trong `knowledge/tuvi/number.vi.jsonl`, cùng định dạng và vòng đời với kho hiện có (`key, school, lang, content, source_ref, status`), import lúc khởi động, khóa đăng ký trong `KeyCatalog`, qua `KnowledgeQualityTest` (STYLE.md: xưng "ta", gọi "con", ≤ 320 ký tự, không từ cấm, không phán chắc, mặt chưa tốt kèm điều con có thể làm).
- Khóa (khoảng 110 bản ghi):
  - `so:intro=sim|bank|plate`
  - `so:menh=kim|moc|thuy|hoa|tho`
  - `so:element=sinh_nhap|tuong_hop|khac_xuat|sinh_xuat|khac_nhap`
  - `so:que=<1..64>` (64 quẻ)
  - `so:amduong=can_bang|hoi_lech|lech`
  - `so:cungphi=hop_ca_hai|hop_mot|chua_hop`
  - `so:nut=cao|vua|thap`
  - `so:pair=<bộ số>` cho từng bộ trong bảng
  - `so:band=rat_hop|kha_hop|binh_thuong|chua_hop`
  - `so:closing` (nhắc: đây là tham khảo theo quan niệm dân gian; con người và việc làm mới là chính)
- Ghép: intro → mệnh → ngũ hành → quẻ → âm dương + cung phi → cặp số (tối đa 3) → band → closing.
- Riêng với số tài khoản và biển số, không viết câu khuyên đổi số; với SIM được nói "nếu con định chọn số mới thì…" nhưng không gợi ý số cụ thể.

## 7. API và giới hạn lượt

**reading-tuvi** `POST /tuvi/number-check` (internal, `X-Internal-Token` + `Authorization: Bearer <session>`, header `X-Client-Ip`):
- Body: `{kind: "sim"|"bank"|"plate", vehicle?: "car"|"motorbike" (bắt buộc khi plate), number: string, birthYear: int, beforeTet: bool, gender: "male"|"female"}`.
- 200: `{score, band, menh {canChi, napAm, element}, cungPhi {name, group}, criteria [{key, label, detail, points, max}], reading [string], quota {limit, used, remaining}}`. Không trả lại dãy số.
- 400 `invalid_number` / `invalid_birth`; 409 `number_quota` (kèm quota); Problem Details + `trace_id` như mọi lỗi.
- `GET /tuvi/number-check/quota` → `{limit, used, remaining}`.
- Thêm vào `contracts/openapi/reading-tuvi.yaml`.

**Đếm lượt** (Flyway migration mới, bảng `number_check_usage`):
- Cột: `subject_kind`, `subject_id`, `vn_day date`, `check_hash bytea`, `ip_hash bytea`, `created_at`. Khóa chính `(subject_kind, subject_id, vn_day, check_hash)`.
- `check_hash` = HMAC-SHA256(khóa bí mật, `kind|số đã chuẩn hóa`). Không gồm năm sinh/giới tính: xem cùng một số cho cả nhà trong ngày không trừ thêm lượt.
- Khách đăng nhập giữa ngày: không gộp lượt khách sang tài khoản (khác Q&A). Giới hạn theo IP chặn việc lạm dụng cách này. `ip_hash` = HMAC-SHA256(khóa, IP; IPv6 lấy /64). Khóa mới `NUMBER_CHECK_HMAC_KEY` trong secret của reading-tuvi (thêm vào `secrets.tsv`, e2e env, `.env.example`).
- Một lượt: trong một transaction (advisory lock theo subject): nếu `check_hash` đã có hôm nay → trả kết quả, không đếm. Nếu subject đã có ≥ `NUMBER_CHECK_DAILY_PER_SUBJECT` (2) hash khác → 409. Nếu số hash khác nhau theo `ip_hash` hôm nay ≥ `NUMBER_CHECK_DAILY_PER_IP` (mặc định 20, rộng vì nhiều người dùng chung IP qua mạng di động) → 409. Ngược lại ghi hàng rồi trả kết quả.
- Dọn dẹp: job định kỳ (như `adapter.in.schedule` hiện có) xóa hàng có `vn_day` < hôm nay − 1.
- Không cần cache kết quả: tính toán rẻ và tất định.

**web**: route `POST /api/xem-so` theo mẫu `app/api/qa/readings/route.ts`: `csrfRefusal` → `readObject` + kiểm tra trường → `ensureSession()` → gọi reading-tuvi với IP từ `lib/clientIp.ts` → `upstreamFailure(e, "xem-so")` (409 là kỳ vọng, không log). Response `Cache-Control: no-store`. Thêm `/api/xem-so` vào đường dẫn của Ingress `web-strict` (20 lần/phút/IP). Trang đọc quota qua `GET` tương ứng để hiện "còn N lượt".

## 8. Riêng tư (kiểm chứng được)

- Dãy số chỉ đi trong body POST web → reading-tuvi, qua mạng nội bộ cluster.
- Không có log nào chứa dãy số: mở rộng `NoPiiInLogsIT` với một lượt xem số (khẳng định log không chứa dãy số và năm sinh); test web tương tự cho route.
- DB chỉ có HMAC, xóa sau 2 ngày. Không gửi analytics/sự kiện chứa dãy số.
- Không có URL nào chứa dãy số; form dùng POST bằng JS, không `action=GET`.

## 9. Kiểm thử và hoàn thành

- Unit test domain: chuẩn hóa (hợp lệ/không), bảng nạp âm (đủ 60 năm), cung phi (các ca ở 5.2 + năm 1930, 1999, 2001, 2026), quẻ (một vài dãy tính tay), từng tiêu chí, làm tròn tổng, ví dụ đầu-cuối cố định (golden).
- Quota: test tích hợp (Testcontainers Postgres) cho: lượt 1, 2 ok; lượt 3 bị 409; xem lại số cũ không trừ; sang ngày mới reset; giới hạn IP.
- Kho nội dung: mọi khóa ở mục 6 có bản ghi `approved`; qua QualityRules.
- Web: unit test form/route; trang trong sitemap (44 → 48 URL), metadata indexable; e2e Playwright: nhập số → thấy điểm; lượt thứ 3 → thấy thông báo hết lượt.
- Cổng: `mvn -q verify` (coverage domain/application ≥ 80%, ArchUnit), web gates, k8s-validate (secret mới), CI xanh.

## 10. Để builder tự quyết

Tên class/record, bố cục component React, câu chữ cụ thể của ~110 bản ghi (theo STYLE.md), bảng 64 quẻ cát/hung theo nguồn phổ biến, danh sách bộ số dân gian đầy đủ (giữ trong bảng ở mục 5.3), cách tách PR.

# F2 — Chiêm tinh ("Người Giữ Sao") — Design addendum

- Ngày: 2026-11-28 · Trạng thái: **Đã duyệt** (Tuấn, 2026-11-28) · Owner: Tuấn · Builder: Claude
- Bổ sung cho `docs/superpowers/specs/2026-11-24-fortune-platform-design.md`. Những gì addendum này nói khác spec gốc thì addendum thắng (ghi rõ ở §9). Chỗ không nhắc đến thì giữ nguyên spec gốc: AI không tự tính lá số, chỉ luận từ kho; gói thẻ và lượt (§14); rủi ro pháp lý (§15); không quảng cáo (§16).

## 1. Mục đích & lý do

Trang thứ hai của sản phẩm fortune: **chiêm tinh phương Tây (natal chart)**. Nhân vật dẫn dắt là **Người Giữ Sao**, không khí bầu trời đêm, đồng thau. Trang chạy trên cùng hạ tầng với tử vi (`xemtuvingay.com`) nhưng **đối với khách là một thương hiệu riêng**: tên miền riêng, tài khoản riêng, ví lượt riêng, giá riêng. Lời Tuấn: "khách đâu biết cùng 1 hệ thống".

Trang ra mắt bằng **tiếng Việt**. Từ đầu đã thiết kế **i18n** và dùng tên miền quốc tế để sau mở cho khách nước ngoài (tiếng Anh, rồi tiếng Trung phồn thể) mà không phải làm lại.

Điểm khác biệt vẫn như tử vi:
1. Lá số do engine tính và được kiểm chứng bằng bộ golden.
2. Luận giải chỉ lấy từ kho dựng từ sách thuộc phạm vi công cộng.
3. Phần miễn phí không gọi LLM.

## 2. Goals / Non-goals / Anti-goals

**Goals**
- A1. Khách nhập ngày, giờ, nơi sinh và nhận ngay bản đồ sao đúng (vòng natal SVG) kèm luận cơ bản, **miễn phí, không cần đăng nhập**. Không biết giờ sinh vẫn xem được, chỉ là không có nhà và cung mọc (§4.3).
- A2. Mục "Hôm nay" miễn phí lấy từ transit của Mặt Trăng (và các hành tinh nhanh) so với bản đồ gốc, không gọi LLM.
- A3. Hỏi theo chủ đề (trả phí theo lượt) qua LLM, chỉ dựa trên kho, bằng giọng Người Giữ Sao (xưng "ta", gọi "bạn"). Chủ đề giống tử vi: tính cách, tình duyên, sự nghiệp, tài lộc, sức khỏe, gia đạo, vận năm.
- A4. i18n từ ngày đầu. Ngôn ngữ đầu tiên chọn theo IP: Việt Nam → `vi`, còn lại → `en` khi đã bật, chưa bật thì về `vi`. Luôn có nút đổi ngôn ngữ, lựa chọn nhớ bằng cookie. URL dạng `/{locale}/...`, có `hreflang`. Ra mắt chỉ bật `vi`.
- A5. Mọi nội dung hiển thị (kho, khung lời thoại, nhãn giao diện, nhãn ký hiệu) đều có trường ngôn ngữ. Thêm ngôn ngữ mới chỉ là thêm dữ liệu và bản dịch, không sửa code.
- A6. SEO: trang kiến thức tĩnh (12 cung hoàng đạo, 10 hành tinh, 12 nhà, các góc chiếu) với slug theo ngôn ngữ.

**Non-goals (F2)**
- Tiếng Anh và tiếng Trung *nội dung*. Cấu trúc đã sẵn, nội dung làm ở F3.
- PayPal/Paddle và thanh toán quốc tế (F3, sau khi xác nhận chính sách §15).
- Xem hợp đôi (synastry), solar return, progressions.
- Hoàng đạo sidereal/Vệ Đà, các nhà Koch/Campanus, tiểu hành tinh ngoài Chiron.

**Anti-goals**
- Engine hoặc dữ liệu có giấy phép bắt buộc mở mã (AGPL/GPL) hay phải trả phí mà Tuấn chưa duyệt.
- Khách nhìn ra hai trang là một hệ thống: email, tên người gửi, chữ trên giao diện hay URL lộ tên trang kia.
- Luận giải "bịa" ngoài kho, hoặc trộn hệ thống (ví dụ cung tropical lẫn sidereal) trong cùng bản đồ.
- Làm hỏng hay chậm trang tử vi đang chạy thật.

## 3. Ràng buộc

- Cùng host prod `tuvi-prod-1` (12 GB). Phần chiêm tinh thêm khoảng 1 GB RAM: chart ~20 MB, reading ~512 MB, web ~250 MB, thêm một database trên Postgres có sẵn.
- Máy dev yếu: build và kiểm chứng nặng chạy trên CI. Golden fixtures sinh một lần rồi commit.
- `deny.toml` chỉ cho phép giấy phép kiểu MIT/Apache/BSD/MPL (không có AGPL/GPL). Engine phải qua được `cargo deny`.
- Dữ liệu sinh là dữ liệu cá nhân, thêm **nơi sinh/tọa độ**: mã hóa at-rest như ngày giờ sinh, không ghi log, không gửi cho LLM (chỉ gửi bản đồ đã ẩn danh, §7 spec gốc).

## 4. Quyết định

### 4.1 Của Tuấn (2026-11-28)

1. **Ngôn ngữ**: thiết kế i18n ngay. IP chọn ngôn ngữ mặc định, có nút đổi. Ra mắt `vi` trước.
2. **Tên miền**: tên `.com` quốc tế để mở rộng ra nước ngoài, chạy cùng host với tử vi. Tuấn tự mua. Ứng viên Claude đã tra registry Verisign ngày 2026-11-28, cả bốn đều chưa ai đăng ký:
   - `mystarkeeper.com` (Claude đề xuất)
   - `askstarkeeper.com`
   - `starkeeperastro.com`
   - `starkeeperchart.com`

   Phải tra trùng nhãn hiệu trước khi mua. Trong repo tên miền chỉ là giá trị cấu hình `ASTRO_DOMAIN`. F2.1 không phụ thuộc tên miền.
3. **Mua hàng tách riêng mỗi trang**: tài khoản, ví lượt, thẻ tháng/năm và bảng giá riêng cho từng trang. Cùng một email có thể có hai tài khoản độc lập ở hai trang.
4. Tuấn giao phần "làm thế nào" cho Claude ("ok, làm đi"). Mọi mục ở §4.2 trở đi là Claude's call, Tuấn sửa được khi duyệt.

### 4.2 Kiến trúc (Claude's call)

```
web-astro (Next.js, next-intl)  ─────────────►  astro-chart-svc (Rust, stateless, :8086)
   │  BFF: /api/{qa,orders,auth,places}             POST /astro/chart, POST /astro/transits
   ├─► reading-astro (Java, :8087, DB reading_astro) ─► astro-chart-svc
   │      dùng chung libs/java/reading-core với reading-tuvi
   ├─► identity-svc (site-aware: account.site = tuvi|astro)
   └─► payment-svc  (price theo site; PaymentSucceeded.site đã có)
```

- **astro-chart-svc** là service Rust mới, tạo bằng `scripts/new-service.sh --lang rust --name astro-chart-svc --port 8086`. Không nhét vào chart-svc vì crate đó toàn tên Ziwei, có kiến trúc test và thông báo bên thứ ba riêng, và tách ra thì test độc lập được.
- **reading-astro** là service Java mới. Phần chung của reading-tuvi (LLM gateway, QA store/cipher/cache/jobs, `PiiScrubber`, importer JSONL) được **tách thành `libs/java/reading-core`**, có điểm cắm cho từng trang:
  - kiểu khóa kho,
  - danh sách ngôn ngữ và chủ đề,
  - persona và prompt,
  - bộ lọc đầu ra theo ngôn ngữ.

  reading-tuvi chuyển sang dùng lib này mà **không đổi hành vi**; bộ test hiện có là lưới an toàn. Việc tách chỉ bắt đầu **sau khi nhánh `feat/xem-so-hop-menh` đã merge**, để không đụng file với session khác.
- **web-astro** là app Next.js mới (`services/web-astro`), không dùng chung codebase với web tử vi qua chọn theo hostname. Lý do: web tử vi chỉ có tiếng Việt, route viết cứng `/vi/...`, không có thư viện i18n, và theme hai trang gần như không chung gì. Gắn i18n vào đó sẽ làm rủi ro cho trang đang chạy thật. Code BFF (auth, đơn hàng, QA) được chép sang rồi sửa riêng, vì repo chưa có npm workspace. Trùng lặp này chấp nhận được.
- **identity-svc** (site-aware):
  - Bảng `account` thêm `site` (`tuvi|astro`), unique `(site, email)`. Dữ liệu cũ mặc định `tuvi`. Sổ lượt và thẻ nằm dưới account, nên tự tách theo trang.
  - Hồ sơ chiêm tinh là bảng riêng: ngày giờ địa phương, IANA zone, vĩ độ/kinh độ, nhãn nơi sinh, `time_known`; mã hóa như hồ sơ tử vi. Vẫn một hồ sơ cho mỗi tài khoản, khóa sau lượt đầu tiên (§13.3 spec gốc).
  - Email magic-link theo trang: người gửi, tiêu đề, base URL và mẫu thư lấy từ cấu hình của site, theo ngôn ngữ người dùng.
- **payment-svc**:
  - Khóa giá đổi thành `(site, product_code, currency)`, vẫn các mã gói CREDIT_1/CREDIT_5/PASS_MONTH/PASS_YEAR. Giá VND khởi điểm của astro bằng tử vi; Tuấn chỉnh sau.
  - Webhook SePay và tài khoản nhận tiền dùng chung; khớp đơn theo mã đơn (đã có tiền tố theo site).
  - *Lưu ý cho Tuấn*: mã VietQR hiện **tên chủ tài khoản ngân hàng**, nên khách chuyển tiền ở cả hai trang sẽ thấy cùng một tên. Muốn tách hẳn thì cần tài khoản nhận thứ hai. Việc này là của Tuấn, không chặn F2.
- **Hạ tầng**:
  - `ASTRO_DOMAIN` đặt cạnh `APP_DOMAIN` trong `overlays/prod/domain`. Có Ingress riêng cho web-astro và base/overlay riêng cho ba service mới.
  - Thêm public hostname trên **cùng named tunnel** Cloudflare (Tuấn làm trong dashboard), cùng DNS cho domain mới.
  - Thêm database `reading_astro` (init-db và runbook).

### 4.3 Engine bản đồ sao (Claude's call)

**Hệ thống (một "trường phái", ghi trong trường `school`):**
- Hoàng đạo **tropical**, tọa độ địa tâm, vị trí biểu kiến của ngày (apparent, ecliptic of date).
- Nhà **Placidus** mặc định; có thêm Whole Sign và Equal.
- Dùng **True Node**, góc chiếu Ptolemaic.
- Giá trị `school`: `"tropical;houses=<system>;node=true;aspects=ptolemaic-v1"`.

**Thiên thể:** Sun, Moon, Mercury, Venus, Mars, Jupiter, Saturn, Uranus, Neptune, Pluto, NorthNode (true), Chiron, cùng hai góc Asc và MC.

**Nguồn tính (license-safe, toàn bộ là Rust thuần):**

| Phần | Nguồn | Giấy phép |
|---|---|---|
| Mercury–Neptune, Trái Đất (cho Mặt Trời) | crate `vsop87` 3.0.0 (VSOP87D) | MIT/Apache-2.0 |
| Mặt Trăng | Meeus, *Astronomical Algorithms* ch.47 (ELP-2000/82 rút gọn, ~10″) | công thức tự cài |
| Pluto, Chiron | bảng tính sẵn từ **JPL Horizons** (kinh độ và vĩ độ hoàng đạo biểu kiến địa tâm của ngày, 5 ngày một điểm, 1900–2100, nội suy Lagrange 4 điểm), commit trong repo, ghi công "JPL Solar System Dynamics, Horizons". Pluto dùng bảng chứ không dùng Meeus ch.37, vì công thức đó chỉ hợp lệ đến 2099 và bảng ít code hơn | dữ liệu JPL; Tuấn/Claude gửi email xin xác nhận dùng thương mại trước khi mở bán |
| True Node | node mật tiếp (osculating), tính từ vị trí và vận tốc Mặt Trăng (r × v) | tự cài |
| Nutation, độ nghiêng hoàng đạo, ΔT | Meeus ch.22 (IAU 1980), đa thức ΔT Espenak–Meeus (NASA) | công thức |
| Quang sai, light-time, hiệu chỉnh FK5 | Meeus ch.25/33 | công thức |
| Asc, MC, nhà | công thức chuẩn (Placidus lặp), Whole Sign, Equal | tự cài |

- Phương án dự phòng nếu VSOP87/Meeus không đạt golden: port phần cần của **Astronomy Engine** (MIT, C) sang Rust.
- Swiss Ephemeris **không dùng**: AGPL bị `deny.toml` chặn; bản Professional giá 700 CHF cho 6 năm, chỉ cân nhắc nếu Tuấn muốn trả tiền.

**Độ chính xác (golden, chạy trong CI):**
- Mọi thiên thể lệch ≤ **1′** so với JPL Horizons. So bằng quantity 31: kinh độ hoàng đạo biểu kiến của ngày, địa tâm `500@399`. Bộ test khoảng 60 thời điểm rải đều 1900–2100, gồm các ca biên như hành tinh nghịch hành và Mặt Trăng sát ranh giới cung.
- Asc, MC và đỉnh các nhà lệch ≤ **1′** so với bộ tham chiếu khoảng 40 ca (nhiều vĩ độ, kể cả Nam bán cầu và gần 66°).
  - Bộ tham chiếu sinh **một lần** bằng script dev chạy Swiss Ephemeris trong container Docker dùng xong bỏ.
  - Chỉ commit con số kết quả, kèm README nói rõ nguồn. Swiss Ephemeris không vào repo, không vào image, không vào dependency.
- Có thêm property test: đỉnh nhà 7 = nhà 1 + 180°, nhà 10 = MC, các đỉnh tăng đơn điệu.
- True Node đối chiếu với **cùng bộ tham chiếu Swiss Ephemeris dùng cho nhà** (Horizons không cho node), ngưỡng ≤ 1′.
  - Nếu node mật tiếp từ Mặt Trăng Meeus không đạt, thử công thức mean node cộng các số hạng tuần hoàn chính.
  - Vẫn không đạt thì **nới ngưỡng riêng cho Node lên 5′** và ghi vào `docs/lessons`. Node chỉ được luận theo cung và nhà, nên 5′ chỉ ảnh hưởng ở sát ranh giới.
  - Không vì Node mà chuyển sang phương án E2.

**Hành vi:**
- Đầu vào là ngày giờ địa phương, IANA zone, vĩ độ, kinh độ, `time_known`, `house_system` (`placidus` mặc định, `whole-sign`, `equal`; người dùng chọn được ở trang hồ sơ). Năm hỗ trợ: 1900–2100.
- Offset lịch sử lấy từ tz database (`jiff`), làm **giống chart-svc**:
  - giờ rơi vào khoảng trống DST → 400 trên field `birth` ("does not exist in the zone (DST gap)");
  - giờ rơi vào khoảng chồng DST → lấy thời điểm sớm hơn.

  Khác chart-svc ở hai điểm: không có quy ước giờ Tý và không chuẩn hóa về giờ chuẩn, vì chiêm tinh cần đúng thời điểm tuyệt đối.
- `time_known=false`: tính lúc 12:00 trưa giờ địa phương, **không** trả nhà/Asc/MC. Nếu Mặt Trăng đổi cung trong ngày thì gắn `moon_sign_uncertain: true`.
- Vĩ độ trên 66°33′ thì Placidus không xác định. Khi đó dùng Whole Sign và gắn `house_fallback: "whole-sign"`.
- Góc chiếu và orb:

  | Góc chiếu | Orb |
  |---|---|
  | conjunction 0° | 8° |
  | opposition 180° | 8° |
  | trine 120° | 8° |
  | square 90° | 7° |
  | sextile 60° | 6° |

  - Cặp có Mặt Trời hoặc Mặt Trăng thì cộng thêm 2°.
  - Cặp có Node hoặc Chiron dùng orb **cố định 3°**, thay cho mọi quy tắc trên. Node chỉ có conjunction và opposition; Chiron đủ năm góc.
  - Asc và MC chỉ có khi `time_known`. Hành tinh tạo đủ năm góc với chúng, orb 5° (đã gồm phần cộng của Mặt Trời và Mặt Trăng). Asc và MC không tạo góc chiếu với nhau, với Node hay với Chiron.
  - Mỗi cặp chỉ lấy góc chiếu có độ lệch nhỏ nhất. Mỗi góc chiếu ghi `orb` (độ lệch so với chính xác) và `applying`.
- Nghịch hành xác định bằng tốc độ kinh độ âm (sai phân trung tâm ±0,5 ngày).
- Đầu ra **chỉ có khóa ASCII**, không có nhãn: `Aries`…`Pisces`, `Sun`…`Chiron`, `Asc`/`MC`, nhà `1`…`12`, góc chiếu `conjunction|opposition|trine|square|sextile`. Nhãn theo ngôn ngữ nằm ở web và kho. Khác chart-svc vì có i18n.
- Thuần hàm: trên cùng một kiến trúc CPU, cùng input cho ra JSON giống nhau từng byte. Số làm tròn cố định 4 chữ số thập phân độ.
  - Giữa amd64 và arm64 không đòi giống từng byte, vì `vsop87` dùng `sin`/`cos` của nền tảng.
  - Golden so theo ngưỡng (1′), nên không phụ thuộc kiến trúc. Snapshot byte-exact chỉ chạy trên CI amd64.
- **Transit**: `POST /astro/transits {birth (như request /astro/chart), date (YYYY-MM-DD), zone (múi giờ người xem)}`.
  - Service tự tính lại bản đồ gốc từ dữ liệu sinh, không tin bản đồ do client gửi.
  - Trả dữ kiện tại **12:00 trưa của `date` theo `zone`**:
    - cung của Mặt Trăng transit,
    - nhà natal mà Mặt Trăng đang đi qua. Khi `time_known=false` không có nhà natal, nên dùng nhà tính theo cung Mặt Trời (solar houses, whole-sign từ cung Mặt Trời natal) và ghi `house_basis: "solar"`;
    - pha trăng (8 pha);
    - góc chiếu của Mặt Trăng, Mặt Trời, Sao Thủy, Sao Kim, Sao Hỏa transit lên các hành tinh natal, Asc và MC. Orb transit: Mặt Trăng 3°, các hành tinh khác 2°.
  - Thêm `moon_ingress`: giờ địa phương Mặt Trăng sang cung mới, nếu có trong ngày.

  Đây là dữ kiện cho "Hôm nay", tương tự `/tuvi/daily`.

### 4.4 Kho luận giải chiêm tinh (Claude's call)

- **Nguồn: chỉ phạm vi công cộng**, là sách tiếng Anh in trước 1930 có trên Project Gutenberg hoặc Internet Archive, kiểm `license_basis` từng cuốn lúc build. Danh sách dự kiến:
  - Alan Leo: *Astrology for All*, *How to Judge a Nativity*, *The Key to Your Own Nativity*. Tác giả mất 1917.
  - Sepharial: *A Manual of Astrology*. Tác giả mất 1929.
  - Ptolemy: *Tetrabiblos*, bản dịch Ashmand năm 1822.
  - William Lilly: *Christian Astrology* (1647).
- **Pluto và Chiron không có nguồn công cộng.** Pluto được phát hiện năm 1930, Chiron năm 1977. Hai thiên thể này vẫn hiện trên vòng natal, có trang kiến thức chung, nhưng **không có bản ghi luận giải** ở F2. Luận cơ bản và Q&A không nhắc đến; nếu bị hỏi thì Người Giữ Sao nói kho chưa có tư liệu. Cách này đúng luật cứng ở §11 spec gốc.
- **Khóa bản ghi**:

  | Loại | Ví dụ |
  |---|---|
  | Hành tinh trong cung | `astro:planet=Venus:sign=Taurus` |
  | Hành tinh trong nhà | `astro:planet=Venus:house=7` |
  | Góc trong cung | `astro:angle=Asc:sign=Leo` |
  | Góc chiếu | `astro:aspect=Moon-square-Sun` (cặp sắp theo thứ tự cố định) |
  | Transit trong ngày | `astro:transit:moon:house=7`, `astro:transit:aspect=Moon-trine-Venus` |

  - `school` của kho = `tropical-v1`. Bản ghi "hành tinh trong nhà N" **không phụ thuộc hệ nhà**, dùng chung cho Placidus, Whole Sign, Equal và cả nhà dùng dự phòng. Reading map `school` của engine sang `tropical-v1`.
  - Trường `lang` nhận `vi|en|zh-Hant`; F2 chỉ soạn `vi`.
- **Một hệ chủ tinh duy nhất**: theo Alan Leo, nguồn chính. Uranus chủ Aquarius, Neptune chủ Pisces, Mars chủ Scorpio (không dùng Pluto). Lilly và Ptolemy chỉ dùng cho bản chất hành tinh và cung, không được trái với hệ của Leo.
  - Có schema riêng `contracts/knowledge/astro-record.schema.json`.
- **Biên soạn** theo §12.4 spec gốc: Claude soạn lúc build, commit JSONL vào `knowledge/astro/`. Lúc chạy không bao giờ gọi Claude.
- Quy mô ước tính khoảng 450 bản ghi luận, cộng khung lời thoại và nhóm "ngày". Bộ eval 150 câu, ngưỡng bám tư liệu ≥ 95% như §11.

### 4.5 Web-astro (Claude's call)

- Next.js cùng phiên bản với web tử vi, dùng `next-intl`.
  - Locale khai báo `vi`, `en`, `zh-Hant`; cấu hình `ENABLED_LOCALES=vi` khi ra mắt.
  - Slug theo ngôn ngữ (next-intl `pathnames`), ví dụ `/vi/cung-hoang-dao/bach-duong`, sau này `/en/zodiac/aries`.
- Chọn ngôn ngữ (middleware), theo thứ tự ưu tiên:
  1. cookie `NEXT_LOCALE` (nút đổi ngôn ngữ ghi cookie này),
  2. `CF-IPCountry`: `VN` → `vi`, nước khác → `en` nếu đã bật,
  3. `Accept-Language`,
  4. locale mặc định `vi`.

  Bot tìm kiếm luôn được phục vụ đúng URL đã yêu cầu, không bị chuyển hướng theo IP.
- Nơi sinh: dataset **GeoNames cities15000** (CC BY 4.0, ghi công ở trang giới thiệu) đóng gói phía server. BFF `/api/places?q=` trả tên, lat/lon và IANA zone. Không gọi API bên ngoài.
  - Tìm kiếm bỏ dấu trên `name`, `asciiname` và `alternatenames`.
  - Tên hiển thị theo ngôn ngữ lấy từ một lát lọc sẵn của GeoNames `alternateNamesV2` (chỉ các thành phố trong cities15000, chỉ `vi|en|zh-Hant`), làm lúc build. Không có tên theo ngôn ngữ thì dùng `name`.
  - Tên nước theo ngôn ngữ là một bảng nhỏ.
- Theme theo §13–14 spec gốc:
  - bầu trời đêm, viền đồng;
  - tiêu đề Great Vibes; lời Người Giữ Sao bằng Cormorant Garamond 18–19px trong khung kính đêm viền đồng; chữ thường Spectral; ô nhập chỉ có gạch chân đồng;
  - font tự host (OFL), theo chính sách font của web tử vi;
  - hiệu ứng chữ sáng như bụi sao, có tôn trọng `prefers-reduced-motion`.
- Vòng natal là SVG vẽ từ JSON của engine. Nhãn và glyph lấy theo locale.
- Không chữ nào nhắc đến tử vi hay `xemtuvingay.com` (anti-goal).

### 4.6 SEO "tự cập nhật" (Tuấn hỏi 2026-11-28; Claude's call)

Google đổi thuật toán liên tục và phạt các mẹo:
- nhồi từ khóa,
- trang sinh hàng loạt mà nội dung mỏng (chính sách "scaled content abuse"),
- mua link.

Vì vậy không có cách "động" nào để lách thứ hạng. Phần làm được là **tự động hóa những thứ Google luôn thưởng** và **đo rồi sửa theo dữ liệu**:

1. **Nội dung tươi tự sinh, không LLM:** trang "chiêm tinh hằng ngày / tuần / tháng" cho 12 cung, ví dụ `/vi/chiem-tinh-hom-nay/bach-duong`.
   - Nội dung dựng từ transit thật (engine F2.1) cộng kho, nên mỗi ngày mỗi khác và có căn cứ.
   - Tự làm mới mỗi ngày (ISR); `lastmod` trong sitemap đổi theo.
   - Thêm trang năm (`/vi/chiem-tinh-2027/...`) giống tử vi 2027.
   - Đây là nhóm từ khóa nhiều lượt tìm nhất của chiêm tinh tại Việt Nam.
2. **SEO kỹ thuật tự động:** sitemap sinh từ route và nội dung kèm `lastmod`; `hreflang` và canonical cho từng locale; JSON-LD (`Article`, `FAQPage`, `BreadcrumbList`); thẻ Open Graph và ảnh chia sẻ.
   - **CI kiểm Core Web Vitals** (ngân sách Lighthouse cho LCP, CLS, kích thước JS). Trang chậm thì PR đỏ.
3. **Vòng đo → sửa:** job hằng tuần đọc **Google Search Console API**.
   - Lọc ra trang có nhiều lượt hiển thị nhưng CTR thấp, hoặc đứng hạng 5–20.
   - Tạo issue liệt kê các trang đó. Claude sửa tiêu đề, mô tả và nội dung theo dữ liệu, mỗi lần một PR.
   - Cần Tuấn thêm domain vào Search Console và cấp quyền service account lúc go-live.
4. Thuộc F2.5. Phần 2–3 dùng lại được cho trang tử vi (đề xuất làm thành follow-up riêng).

Ghi chú (Tuấn, 2026-11-28): tên miền và các việc build/go-live để sau. Trước mắt cần kiến trúc và engine; domain, tunnel và SEO go-live thêm ở F2.5.

## 5. Approaches đã cân nhắc

| | Cách | Kết quả |
|---|---|---|
| **K1** | Service riêng cho astro (chart, reading, web), dùng chung identity và payment (đã site-aware), reading dùng chung lib `reading-core` | **Chọn**. Không động vào trang tử vi đang chạy, test độc lập, i18n làm sạch từ đầu |
| K2 | Như spec gốc: thêm `/astro/*` vào chart-svc, cùng image reading deploy hai bản, một web chọn theo hostname | Loại: code reading và web hiện gắn chặt với tử vi (khóa `^tuvi:`, persona, route `/vi/...`, không có i18n); sửa lại sẽ làm rủi ro cho trang tử vi |
| K3 | Chép nguyên reading-tuvi sang reading-astro rồi sửa | Loại: nhân đôi phần nhạy cảm (an toàn đầu ra, mã hóa, trừ/hoàn lượt) nên sửa lỗi phải sửa hai nơi |

| | Engine | Kết quả |
|---|---|---|
| **E1** | `vsop87` (MIT/Apache) + công thức Meeus + bảng JPL cho Chiron, Rust thuần | **Chọn**. Nhỏ, kiểm chứng được, dư sức đạt 1′ |
| E2 | Port Astronomy Engine (MIT) sang Rust | Dự phòng nếu E1 không đạt golden; khối lượng port lớn hơn |
| E3 | Swiss Ephemeris | Loại: AGPL bị chặn; bản trả phí 700 CHF chỉ dùng khi Tuấn quyết |

## 6. Lộ trình F2 (Claude's call)

Mỗi plan là một PR hoặc một loạt PR, test độc lập:

1. **F2.1 — astro-chart-svc**: engine, `/astro/chart`, `/astro/transits`, golden so với JPL Horizons và bộ tham chiếu nhà, base k8s và overlay e2e. Không phụ thuộc gì, làm được ngay.
2. **F2.2 — tách theo trang**:
   - `libs/java/reading-core`, chỉ làm sau khi `feat/xem-so-hop-menh` đã merge;
   - identity `account.site`, hồ sơ chiêm tinh, mail theo site;
   - payment giá theo site.

   Tử vi không đổi hành vi; smoke tử vi phải xanh.
3. **F2.3 — kho chiêm tinh (vi) + reading-astro**: luận cơ bản và "Hôm nay" không LLM, khung lời Người Giữ Sao.
4. **F2.4 — Q&A chiêm tinh**: prompt và persona, bộ lọc đầu ra tiếng Việt cho giọng "ta/bạn", eval 150 câu.
5. **F2.5 — web-astro + go-live**:
   - i18n, theme, vòng natal, nơi sinh, mua lượt, trang kiến thức SEO;
   - `ASTRO_DOMAIN`, ingress, tunnel hostname.

   Bước tunnel hostname cần Tuấn: mua domain và thêm hostname trong Cloudflare.

## 7. Phần để builder quyết

- Phiên bản crate và thư viện; bước nội suy bảng Chiron (5 ngày là đề xuất, chọn theo sai số đo được); cách mã hóa bảng trong repo.
- Luật chấm "Hôm nay" (thuận/bình/lưu ý) từ transit; khung trình bày luận cơ bản; danh sách trang kiến thức.
- Chi tiết migration identity và payment (F2.2), miễn giữ đúng §4.2.

## 8. Việc của Tuấn

- Mua domain (ứng viên ở §4.1.2) sau khi tra trùng nhãn hiệu, rồi thêm public hostname và DNS trên Cloudflare. Chỉ cần trước F2.5.
- Gửi mail cho domain mới: thêm domain vào SMTP relay đang dùng cho identity-svc, rồi khai SPF/DKIM trên Cloudflare DNS để magic-link của trang chiêm tinh gửi từ địa chỉ của chính domain đó (không gửi từ domain tử vi). Chỉ cần trước F2.5.
- Quyết định có dùng tài khoản nhận tiền thứ hai hay không (§4.2, lưu ý VietQR). Không chặn F2.
- Trước khi mở bán: gửi email xin JPL xác nhận dùng dữ liệu Horizons cho mục đích thương mại (Claude soạn thư).

## 9. Thay đổi so với spec gốc

- §6 / §6.1: không còn "một web chọn theo hostname" và "reading cùng image deploy hai bản". Thay bằng K1 (§4.2).
- §6.3: chiêm tinh chạy ở service riêng `astro-chart-svc`. Engine là E1, không dùng Swiss Ephemeris. Golden so với JPL Horizons thay vì Swiss Ephemeris (Swiss Ephemeris chỉ để sinh bộ tham chiếu nhà, ngoài repo). Đầu ra chỉ có khóa, không có nhãn.
- §6.2 / §13.3: tài khoản và ví lượt **theo từng trang** (spec gốc ngầm là một tài khoản dùng chung).
- §9 lộ trình: "F2 — Chiêm tinh tiếng Việt" tách thành F2.1–F2.5, và có i18n từ F2 thay vì chờ F3.

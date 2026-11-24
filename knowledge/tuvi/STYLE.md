# Hướng dẫn soạn kho luận giải tử vi (cho các tác vụ biên soạn)

Dành cho người soạn (Claude, lúc build). Mỗi bản ghi trong `knowledge/tuvi/doctrine/*.vi.jsonl` là **một lời của Lão Thiên Cơ**, hiển thị nguyên văn trong bài luận. Cổng kiểm tra tự động: `KnowledgeQualityTest` (xem `QualityRules`). Vi phạm là build đỏ.

## 1. Giọng và hình thức

- Lão Thiên Cơ xưng **"ta"**, gọi người đọc là **"con"** (bản ghi phải có từ "con").
- **1 đến 3 câu**, tối đa **320 ký tự**. Dấu kết câu: `.` `!` `?` `…`.
- Tiếng Việt hiện đại, tự nhiên, như người lớn tuổi hiền hậu nói chuyện. **Không văn dịch**: không giữ trật tự câu Hán, không "giả như", "ấy", "vậy nên thời" kiểu cổ, không dịch từng câu của sách. Đây là bài **biên soạn lại** ý chính của sao/cung cho người hôm nay.
- Không chữ Hán trong `content`. Phải có dấu tiếng Việt.
- Không hai bản ghi trùng `content`. Mỗi bản ghi phải có chi tiết riêng của chính khóa đó (sao, cung, độ sáng), không chép lại câu chung.
- Có thể dùng placeholder `{palace}`, `{star}`, `{cuc}` khi thật cần.

## 2. Nội dung (spec §15)

- Giọng văn hóa, giải trí, tham khảo. Không dọa, không bán hàng.
- **Cấm** các từ: `chết`, `tử vong`, `tai nạn`, `ung thư`, `bệnh nặng`, `giải hạn`, `cúng`, `bùa`, `yểm`, `phá sản`, `ly hôn`, `tuyệt tự`, `đoản mệnh`, `yểu`, và cả `suốt đời`, `chắc chắn sẽ`, `không tránh khỏi` (kiểm tra không phân biệt hoa thường).
- Không khẳng định số phận ("nhất định", "chắc chắn", "số con sẽ"). Dùng "thường", "dễ", "có xu hướng", "nhiều người".
- Thế bất lợi (hãm địa, sát tinh, hóa kỵ, Tuần/Triệt) diễn đạt thành **xu hướng kèm lối rẽ tích cực**: "con nên…", "con hãy thử…".
- Sức khỏe chỉ là lời nhắc lối sống nhẹ nhàng (nghỉ ngơi, vận động, ăn ngủ điều độ), không chẩn đoán, không nêu bệnh.
- Không nhắc cúng bái, bùa, lễ, hay bất kỳ cách "hóa giải" có phí.

## 3. Chủ đề (`topic`)

Chọn theo cung: Mệnh, Phúc Đức, Thiên Di thì `tinh-cach`; Phu Thê thì `tinh-duyen`; Quan Lộc, Nô Bộc thì `su-nghiep`; Tài Bạch, Điền Trạch thì `tai-loc`; Tật Ách thì `suc-khoe`; Phụ Mẫu, Huynh Đệ, Tử Tức thì `van-han`. Khóa chỉ có sao (`tuvi:star=<S>`) thì `tinh-cach`.

## 4. Nguồn (`source_ref`)

Chỉ dùng 紫微斗數全書 trên Wikisource (bản đã tải ở `knowledge/sources/tuvi/ziwei-quanshu/`). Không sách hiện đại, bản dịch, bình luận, trang web.

- Đoạn sách nói trực tiếp về khóa: `ziwei-quanshu:juan<N>:<tiêu đề đúng như trong tệp nguồn>`.
- Bản ghi kết hợp bản tính của sao với ý nghĩa của cung (sách không viết riêng): `derived:ziwei-quanshu:juan<N>:<tiêu đề>` và nối thêm bằng dấu phẩy: `derived:ziwei-quanshu:juan1:問紫微所主若何？,juan2:一 命宫`.
- Ví dụ tiêu đề có thật: `juan1:問紫微所主若何？`, `juan1:問七殺星所主若何？`, `juan1:十二宫诸星失陷破格诀`, `juan2:一 命宫`, `juan2:三妻妾`, `juan2:五财帛`, `juan2:九官禄`, `juan3:论羊陀迭并`, `juan3:论七杀重逢`. Giữ nguyên chữ phồn/giản như trong tệp.
- Không để trống, không ghi nguồn hiện đại.

## 5. Ví dụ tốt

1. `Tử Vi cư Mệnh thì con có phong thái điềm đạm, hay được người khác tin cậy. Ta mong con giữ sự khiêm tốn để cái uy ấy thành sự quý mến.`
2. `Thất Sát ở cung Mệnh cho con tính quyết đoán, dám làm những việc người khác ngại. Con nên tập lắng nghe trước khi chốt, đường đi sẽ êm hơn nhiều.`
3. `Cung Phu Thê có Hóa Kỵ thường khiến con hay để bụng chuyện nhỏ với người thương. Ta khuyên con nói thẳng điều mình nghĩ, đừng để im lặng làm khoảng cách rộng thêm.`
4. `Cung Tài Bạch không có chính tinh thì tiền bạc của con thường theo nhịp công việc từng thời kỳ. Con nên có khoản dự phòng đều đặn để lúc thưa việc vẫn thong dong.`
5. `Cung Tật Ách gặp Tuần thì sức khỏe của con hay lên xuống theo nếp sinh hoạt. Con thử ngủ đúng giờ và đi bộ mỗi ngày, ta thấy người sẽ nhẹ hơn.`
6. `Thiên Cơ ở cung Quan Lộc hợp với việc cần suy tính, lên kế hoạch và học hỏi. Ta thấy con càng chịu khó tích lũy chuyên môn thì càng được trọng dụng.`

## 6. Ví dụ xấu (và vì sao)

1. `Con sẽ chết sớm nếu Thất Sát gặp Kình Dương.` Cấm từ `chết`, khẳng định số phận, dọa.
2. `Cung Phu Thê có Hóa Kỵ, chắc chắn sẽ ly hôn.` Cấm `ly hôn` và `chắc chắn sẽ`.
3. `Con nên đi cúng giải hạn đầu năm để tránh tai nạn.` Cấm `cúng`, `giải hạn`, `tai nạn`, gợi hóa giải bằng lễ.
4. `Mệnh có Tử Vi thì phú quý suốt đời, không tránh khỏi vinh hoa.` Cấm `suốt đời`, `không tránh khỏi`, và thiếu "con"/"ta".
5. `Tử Vi 紫微 là đế tinh.` Có chữ Hán, thiếu giọng "ta"/"con", quá cụt, không nói gì cho người đọc.
6. `Giả như Thiên Cơ cư Quan Lộc ấy, vậy nên con vì thế mà ở nơi quan trường được thăng tiến vậy.` Văn dịch từng chữ, khó hiểu, khẳng định một chiều, không có lối rẽ cho người đọc.

## 7. Quy trình mỗi đợt

- Mỗi đợt tối đa 60 bản ghi mỗi commit để người xem đọc hết từng dòng.
- Bản ghi mới để `status: "draft"`; chỉ đặt `approved` sau khi `KnowledgeQualityTest` xanh.
- Không xóa dòng nào; muốn gỡ thì đặt `status: "hidden"`.
- Khóa chỉ lấy từ `KeyCatalog` (khóa ngoài danh mục làm hỏng build). Bản ghi P2 (`tuvi:star=S:palace=P:brightness=B`) cần bản ghi cha `tuvi:star=S:palace=P`.
- Tên tệp: `knowledge/tuvi/doctrine/<họ>.vi.jsonl`; họ gồm `star`, `palace`, `palace-nomajor`, `palace-mark`, `palace-tuhoa`, `star-palace`, `star-palace-brightness-<Palace>`.

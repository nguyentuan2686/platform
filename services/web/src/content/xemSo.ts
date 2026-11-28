// Static copy of the "Xem số hợp mệnh" pages (spec 2026-11-28-xem-so-hop-menh-design.md §4).
// Paragraphs follow knowledge/tuvi/STYLE.md: Lão Thiên Cơ says "ta", calls the reader "con",
// at most 320 characters, no banned words, no fate claims, no advice to change or buy a number.

export const XEM_SO_PATH = "/vi/xem-so";
export const DAILY_LIMIT_NOTE = "Ta không lưu dãy số của con. Con được xem 2 số mỗi ngày.";
export const QUOTA_MESSAGE = "Hôm nay con xem đủ 2 số rồi, mai quay lại với ta nhé.";
export const IP_QUOTA_MESSAGE = "Hôm nay mạng của con đã xem nhiều số rồi, con quay lại sau nhé.";
export const BANK_WARNING = "Chỉ nhập số tài khoản. Không bao giờ nhập mật khẩu, mã OTP hay số thẻ.";

export type XemSoSlug = "sim" | "so-tai-khoan" | "bien-so-xe";
export type XemSoKind = Readonly<{
  slug: XemSoSlug;
  kind: "sim" | "bank" | "plate";
  name: string;
  title: string;
  description: string;
  numberLabel: string;
  placeholder: string;
  intro: readonly string[];
  method: readonly string[];
}>;

export const HUB_TITLE = "Xem số hợp mệnh: SIM, số tài khoản, biển số xe";
export const HUB_DESCRIPTION =
  "Xem số điện thoại, số tài khoản và biển số xe có hợp mệnh của bạn không: chấm điểm theo ngũ hành, quẻ Kinh Dịch, âm dương và cung phi. Miễn phí.";
export const HUB_INTRO: readonly string[] = [
  "Ta xem một dãy số theo những cách dân gian quen dùng: hành của từng chữ số so với mệnh của con, quẻ Kinh Dịch lập từ hai nửa dãy số, sự cân bằng chẵn lẻ, cung phi Bát trạch, số nút và các cặp số được ưa chuộng.",
  "Con chỉ cần năm sinh và giới tính, không cần giờ sinh. Kết quả là điểm trên thang mười kèm lời ta giảng, để con tham khảo cho vui, còn việc tốt hay chưa vẫn ở cách con sống mỗi ngày.",
];

const METHOD_COMMON: readonly string[] = [
  "Ngũ hành chiếm phần lớn số điểm: mỗi chữ số mang một hành, hành có nhiều chữ số nhất là hành của dãy số, rồi ta so với hành nạp âm theo năm sinh âm lịch của con.",
  "Quẻ Kinh Dịch lập theo lối Mai Hoa: tổng nửa đầu cho quẻ trên, tổng nửa sau cho quẻ dưới. Con sẽ thấy tên quẻ và lời ta giảng về ý nghĩa của nó.",
  "Âm dương, cung phi, số nút và cặp số dân gian là phần thêm, mỗi phần vài điểm nhỏ. Tất cả do máy tính chấm theo một bảng cố định, con xem lại bao nhiêu lần cũng ra cùng kết quả.",
];

export const KINDS: readonly XemSoKind[] = [
  {
    slug: "sim",
    kind: "sim",
    name: "SIM",
    title: "Xem số điện thoại hợp mệnh, hợp tuổi",
    description:
      "Xem số điện thoại (SIM) có hợp mệnh, hợp tuổi của bạn không: điểm hợp mệnh thang 10, quẻ Kinh Dịch, ngũ hành và lời giảng của Lão Thiên Cơ.",
    numberLabel: "Số điện thoại",
    placeholder: "Ví dụ 0912 345 678",
    intro: [
      "Số điện thoại theo con mỗi ngày, nên nhiều người muốn biết nó có hợp mệnh không. Ta xem đủ mười chữ số, theo những tiêu chí dân gian quen thuộc, để con có thêm một góc nhìn vui.",
    ],
    method: METHOD_COMMON,
  },
  {
    slug: "so-tai-khoan",
    kind: "bank",
    name: "Số tài khoản",
    title: "Xem số tài khoản ngân hàng hợp mệnh",
    description:
      "Xem số tài khoản ngân hàng có hợp mệnh của bạn không theo ngũ hành, quẻ Kinh Dịch, âm dương và cung phi. Miễn phí, không lưu số của bạn.",
    numberLabel: "Số tài khoản",
    placeholder: "Từ 6 đến 19 chữ số",
    intro: [
      "Số tài khoản là dãy số gắn với chuyện tiền nong, nên ai cũng tò mò. Ta xem toàn bộ dãy số của con theo lối dân gian, còn tiền bạc vững hay không là nhờ con chi tiêu khéo léo.",
    ],
    method: METHOD_COMMON,
  },
  {
    slug: "bien-so-xe",
    kind: "plate",
    name: "Biển số xe",
    title: "Xem biển số xe hợp mệnh, hợp tuổi",
    description:
      "Xem biển số xe ô tô, xe máy có hợp mệnh của bạn không: chấm điểm phần số thứ tự theo ngũ hành, quẻ Kinh Dịch và cặp số dân gian.",
    numberLabel: "Biển số xe",
    placeholder: "Ví dụ 30A-123.45",
    intro: [
      "Với biển số xe, ta chỉ xem phần số thứ tự ở cuối, không tính mã tỉnh và sê-ri. Con chọn ô tô hay xe máy để ta đọc đúng biển số, và nhớ rằng đi đường bình an là nhờ con cẩn thận.",
    ],
    method: METHOD_COMMON,
  },
];

export function findKind(slug: string): XemSoKind | undefined {
  return KINDS.find((k) => k.slug === slug);
}

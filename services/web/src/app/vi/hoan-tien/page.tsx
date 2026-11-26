import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Chính sách hoàn tiền",
  description: "Khi nào lượt hỏi được hoàn và cách yêu cầu hoàn tiền.",
  robots: { index: true, follow: true },
};

export default function RefundPage() {
  return (
    <main className="legal">
      <h1>Chính sách hoàn tiền</h1>
      <p className="updated">Cập nhật ngày 25/11/2026</p>

      <h2>Câu hỏi không có lời đáp</h2>
      <p>
        Nếu một câu hỏi trả phí không có lời đáp (hệ thống gặp sự cố hoặc quá tải), lượt hỏi được tự động hoàn lại
        vào tài khoản sau tối đa 24 giờ. Bạn không cần làm gì thêm.
      </p>

      <h2>Gói lượt hỏi chưa dùng</h2>
      <p>
        Gói lượt hỏi (1 lượt, 5 lượt) còn nguyên, chưa dùng lượt nào, được hoàn tiền khi bạn yêu cầu. Tiền được
        chuyển khoản lại về tài khoản đã thanh toán.
      </p>

      <h2>Thẻ tháng và thẻ năm</h2>
      <p>Yêu cầu hoàn tiền Thẻ tháng hoặc Thẻ năm được xem xét và xử lý thủ công theo từng trường hợp.</p>

      <h2>Cách yêu cầu</h2>
      <p>
        Vào Tài khoản, mở trang Giao dịch, chọn đơn hàng và bấm yêu cầu hoàn tiền. Bạn sẽ thấy trạng thái yêu cầu
        ngay trên trang đó.
      </p>
    </main>
  );
}

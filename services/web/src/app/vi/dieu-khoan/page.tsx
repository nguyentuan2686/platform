import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Điều khoản sử dụng",
  description: "Điều khoản sử dụng dịch vụ xem lá số tử vi và hỏi đáp.",
  robots: { index: true, follow: true },
};

export default function TermsPage() {
  return (
    <main className="legal">
      <h1>Điều khoản sử dụng</h1>
      <p className="updated">Cập nhật ngày 25/11/2026</p>

      <h2>Tính chất dịch vụ</h2>
      <p>
        Lá số và lời luận giải trên trang là nội dung tham khảo văn hóa và giải trí, dựa trên kinh sách tử vi cổ.
        Nội dung không phải lời khuyên y tế, pháp lý, tài chính hay tâm lý, và không thay thế ý kiến chuyên môn.
        Bạn tự chịu trách nhiệm về các quyết định của mình.
      </p>

      <h2>Những điều chúng tôi không làm</h2>
      <p>
        Chúng tôi không bán và không nhận làm lễ giải hạn, cúng sao, bùa ngải hay bất kỳ dịch vụ tâm linh trả phí
        nào. Lão Thiên Cơ chỉ luận giải lá số; mọi lời mời chào như vậy nhân danh chúng tôi đều là giả mạo.
      </p>

      <h2>Mua lượt hỏi và thẻ</h2>
      <ul>
        <li>Chỉ người đủ 18 tuổi trở lên mới được mua lượt hỏi hoặc thẻ tháng, thẻ năm.</li>
        <li>Giá hiển thị bằng đồng Việt Nam và thanh toán bằng chuyển khoản ngân hàng (VietQR).</li>
        <li>Việc hoàn tiền theo Chính sách hoàn tiền.</li>
      </ul>

      <h2>Sử dụng hợp lý</h2>
      <ul>
        <li>Không gửi câu hỏi nhằm xúc phạm, quấy rối hoặc lấy thông tin của người khác khi chưa được đồng ý.</li>
        <li>Không dùng công cụ tự động để gửi câu hỏi hàng loạt.</li>
        <li>Câu hỏi về bệnh tật, pháp lý hay các nội dung nguy hại có thể bị từ chối; khi đó lượt hỏi không bị trừ.</li>
      </ul>
    </main>
  );
}

import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Chính sách riêng tư",
  description: "Dữ liệu chúng tôi thu thập, cách mã hóa, thời gian lưu và quyền xóa của bạn.",
  robots: { index: true, follow: true },
};

export default function PrivacyPage() {
  return (
    <main className="legal">
      <h1>Chính sách riêng tư</h1>
      <p className="updated">Cập nhật ngày 25/11/2026</p>

      <h2>Dữ liệu chúng tôi thu thập</h2>
      <ul>
        <li>Ngày giờ sinh, giới tính và nơi sinh bạn nhập để lập lá số (chỉ khi bạn đồng ý lưu).</li>
        <li>Địa chỉ email khi bạn đăng nhập bằng email hoặc Google. Email chỉ được hiển thị ở dạng che bớt.</li>
        <li>Câu hỏi bạn gửi cho Lão Thiên Cơ và câu trả lời.</li>
        <li>Đơn hàng và lượt hỏi đã mua, đã dùng.</li>
      </ul>
      <p>
        Chúng tôi không dùng cookie quảng cáo hay theo dõi. Cookie phiên đăng nhập chỉ để nhận ra bạn và không đọc
        được bằng mã chạy trên trình duyệt.
      </p>

      <h2>Mã hóa và lưu trữ</h2>
      <p>
        Ngày giờ sinh, câu hỏi và câu trả lời được mã hóa bằng AES (AES-GCM) trước khi lưu vào cơ sở dữ liệu. Ngày
        giờ sinh không bao giờ xuất hiện trong đường dẫn (URL), nhật ký hệ thống hay trang công khai.
      </p>
      <p>
        Các cuộc trò chuyện miễn phí được lưu tối đa 90 ngày rồi tự động xóa. Câu hỏi trả phí được giữ trong lịch sử
        của bạn cho đến khi bạn xóa.
      </p>

      <h2>Chuyển dữ liệu ra nước ngoài để luận giải bằng AI</h2>
      <p>
        Khi bạn hỏi Lão Thiên Cơ, lá số ẩn danh (không kèm tên, email hay mã tài khoản) và nội dung câu
        hỏi được gửi tới nhà cung cấp AI đặt máy chủ ở nước ngoài để soạn câu trả lời. Việc này chỉ diễn ra sau khi
        bạn đánh dấu đồng ý ở câu hỏi đầu tiên.
      </p>

      <h2>Quyền của bạn</h2>
      <ul>
        <li>Xem lại lá số và lịch sử câu hỏi bất cứ lúc nào.</li>
        <li>Xóa lá số đã lưu và xóa toàn bộ lịch sử câu hỏi ngay trên trang Tài khoản.</li>
        <li>Thu hồi đường dẫn chia sẻ lá số; đường dẫn cũ sẽ không mở được nữa.</li>
        <li>Rút lại sự đồng ý gửi câu hỏi tới nhà cung cấp AI bằng cách không hỏi tiếp và xóa lịch sử.</li>
      </ul>
      <p>
        Sau khi bạn đặt câu hỏi trả phí đầu tiên, ngày giờ sinh của lá số được khóa để tránh dùng một lượt cho nhiều
        người; bạn vẫn có quyền xóa lá số.
      </p>
    </main>
  );
}

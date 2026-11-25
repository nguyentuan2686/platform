import Link from "next/link";

export default function NotFound() {
  return (
    <main>
      <h1>Không tìm thấy lá số</h1>
      <p>Lá số này đã hết hạn hoặc chưa từng được lập. Con hãy nhập lại ngày giờ sinh nhé.</p>
      <Link href="/vi">Lập lá số mới</Link>
    </main>
  );
}

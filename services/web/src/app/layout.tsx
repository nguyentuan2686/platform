import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Tử vi - Lão Thiên Cơ",
  description: "Lá số tử vi và luận giải.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="vi">
      <body>
        {children}
        <footer>Nội dung mang tính tham khảo văn hóa và giải trí.</footer>
      </body>
    </html>
  );
}

import type { Metadata } from "next";
import { PassBanner } from "@/components/PassBanner";
import { SiteHeader } from "@/components/SiteHeader";
import { SiteFooter } from "@/components/SiteFooter";
import { TabBar } from "@/components/TabBar";
import { cfg } from "@/lib/config";
import "@/styles/fonts.css";
import "@/styles/theme.css";

export const metadata: Metadata = {
  metadataBase: new URL(cfg().webOrigin),
  title: { default: "Tử vi - Lão Thiên Cơ", template: "%s · Tử Vi Đẩu Số" },
  description: "Lá số tử vi và luận giải.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="vi">
      <body>
        <SiteHeader />
        <PassBanner />
        {children}
        <SiteFooter />
        <TabBar />
      </body>
    </html>
  );
}

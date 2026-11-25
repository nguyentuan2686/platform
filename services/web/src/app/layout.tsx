import type { Metadata } from "next";
import { Charm, Ma_Shan_Zheng, Noto_Serif, Noto_Serif_Display } from "next/font/google";
import { SiteHeader } from "@/components/SiteHeader";
import { SiteFooter } from "@/components/SiteFooter";
import { TabBar } from "@/components/TabBar";
import "@/styles/theme.css";

const charm = Charm({ weight: ["400", "700"], subsets: ["vietnamese", "latin"], variable: "--font-charm", display: "swap" });
const notoSerif = Noto_Serif({ subsets: ["vietnamese", "latin"], variable: "--font-noto-serif", display: "swap" });
const notoSerifDisplay = Noto_Serif_Display({
  weight: ["500", "700"],
  subsets: ["vietnamese", "latin"],
  variable: "--font-noto-serif-display",
  display: "swap",
});
const maShanZheng = Ma_Shan_Zheng({ weight: "400", subsets: ["latin"], variable: "--font-glyph", display: "swap" });

export const metadata: Metadata = {
  title: "Tử vi - Lão Thiên Cơ",
  description: "Lá số tử vi và luận giải.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html
      lang="vi"
      className={`${charm.variable} ${notoSerif.variable} ${notoSerifDisplay.variable} ${maShanZheng.variable}`}
    >
      <body>
        <SiteHeader />
        {children}
        <SiteFooter />
        <TabBar />
      </body>
    </html>
  );
}

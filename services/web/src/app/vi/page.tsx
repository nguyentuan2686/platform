import type { Metadata } from "next";
import Link from "next/link";
import { BirthForm } from "@/components/BirthForm";
import { JsonLd } from "@/components/JsonLd";
import { Sage } from "@/components/Sage";
import { degradeOrThrow } from "@/lib/degrade";
import { getProfile } from "@/lib/identity";
import { cfg } from "@/lib/config";
import { pageMeta, siteLd } from "@/lib/seo";
import { getViewer } from "@/lib/session";

export const metadata: Metadata = pageMeta({
  origin: cfg().webOrigin,
  path: "/vi",
  title: "Lập lá số tử vi miễn phí, luận giải theo sách cổ",
  description:
    "Nhập ngày giờ sinh để lập lá số tử vi 12 cung theo âm lịch Việt, đọc luận giải miễn phí dựa trên kinh sách cổ cùng Lão Thiên Cơ.",
});

/** True when the viewer already saved a chart. Identity trouble hides the link (logged); a bug rethrows. */
async function hasProfile(): Promise<boolean> {
  try {
    const viewer = await getViewer();
    if (viewer.kind === "anon") return false;
    return (await getProfile(viewer.token)) !== null;
  } catch (e) {
    degradeOrThrow(e, "saved-chart-link");
    return false;
  }
}

export default async function ViHome() {
  const saved = await hasProfile();
  return (
    <main className="home">
      {siteLd(cfg().webOrigin).map((d, i) => (
        <JsonLd key={i} data={d} />
      ))}
      <section className="tv-hero">
        <span className="vert" aria-hidden="true">
          紫微斗數
        </span>
        <h1 className="h-display">Lá số của bạn, đọc theo sách xưa</h1>
        <p className="lead">
          Nhập ngày giờ sinh. Bàn 12 cung được an sao theo âm lịch Việt, luận giải dựa trên kinh sách cổ.
        </p>
        <Sage mood="greet" className="hero-sage" />
      </section>
      {saved && (
        <p className="saved-chart">
          <Link href="/vi/la-so" className="btn btn-ghost">
            Xem lá số của con
          </Link>
        </p>
      )}
      <BirthForm />
      <p className="home-year">
        <Link href="/vi/tu-vi-2027" className="btn btn-ghost">
          Tử vi 2027 cho 12 con giáp
        </Link>
      </p>
      <p className="home-knowledge">
        <Link href="/vi/kien-thuc">Kiến thức tử vi</Link>
      </p>
    </main>
  );
}

import type { Metadata } from "next";
import Link from "next/link";
import { cookies } from "next/headers";
import { QaAsk } from "@/components/QaAsk";
import { Sage } from "@/components/Sage";
import { degradeOrThrow } from "@/lib/degrade";
import { getProfile, type Entitlements } from "@/lib/identity";
import { freeQuota } from "@/lib/qa";
import { AI_CONSENT_COOKIE } from "@/lib/qaRoute";
import { getViewer } from "@/lib/session";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Hỏi thầy", robots: { index: false, follow: false } };

/** A credit, or a pass that is running and still has questions (null = unlimited). */
function canPayFor(e: Entitlements): boolean {
  const pass = e.pass;
  const passOk = pass !== null && Date.parse(pass.ends_at) > Date.now() && (pass.questions_left === null || pass.questions_left > 0);
  return e.credits > 0 || passOk;
}

function NeedChart() {
  return (
    <main className="ask-page">
      <Sage mood="greet" className="ask-sage" />
      <h1 className="h-display">Con lập lá số trước đã.</h1>
      <p className="lead">Ta cần ngày giờ sinh của con để xem cung, sao rồi mới luận được.</p>
      <Link href="/vi" className="btn">
        Lập lá số
      </Link>
    </main>
  );
}

export default async function AskPage() {
  const viewer = await getViewer();
  if (viewer.kind === "anon") return <NeedChart />;
  const profile = await getProfile(viewer.token);
  if (!profile) return <NeedChart />;

  let freeLeft = 0;
  try {
    freeLeft = (await freeQuota(viewer.token)).remaining;
  } catch (e) {
    degradeOrThrow(e, "free-quota"); // quota trouble: free asks are offered as used up (logged); a bug rethrows
  }
  const needsConsent = (await cookies()).get(AI_CONSENT_COOKIE)?.value !== "1";
  return (
    <main className="ask-page">
      <Sage mood="read" className="ask-sage" />
      <h1 className="h-display">Con muốn hỏi về điều gì?</h1>
      <QaAsk
        canPay={viewer.kind === "account" && canPayFor(viewer.entitlements)}
        freeLeft={freeLeft}
        needsConsent={needsConsent}
        isGuest={viewer.kind === "guest"}
      />
      <p className="qa-history">
        <Link href="/vi/lich-su">Bài đã hỏi</Link>
      </p>
    </main>
  );
}

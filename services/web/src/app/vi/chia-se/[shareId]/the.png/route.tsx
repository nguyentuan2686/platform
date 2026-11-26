import { readFile } from "node:fs/promises";
import path from "node:path";
import { ImageResponse } from "next/og";
import { cellOf } from "@/lib/boardLayout";
import { logRefusalOrThrow } from "@/lib/degrade";
import { UpstreamError } from "@/lib/http";
import { loadPublicShare } from "@/lib/publicShare";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const W = 1080;
const H = 1920;
const BOARD_X = 60;
const BOARD_Y = 330;
const CELL_W = 240;
const CELL_H = 290;
const INK = "#f1e6cc";
const GOLD = "#c8a04a";
const GOLD_SOFT = "#e9d49a";

// Next copies src/fonts into the standalone output (outputFileTracingIncludes); cwd is the app root.
const FONT_DIR = path.join(process.cwd(), "src", "fonts");
let fonts: Promise<[Buffer, Buffer]> | undefined;
function loadFonts(): Promise<[Buffer, Buffer]> {
  fonts ??= Promise.all([readFile(path.join(FONT_DIR, "Charm-Bold.ttf")), readFile(path.join(FONT_DIR, "NotoSerif-Regular.ttf"))]);
  fonts.catch(() => {
    fonts = undefined; // retry on the next request
  });
  return fonts;
}

const plain = (status: number) => new Response(null, { status, headers: { "cache-control": "no-store" } });

/** The 1080x1920 share card: only gender-free chart content (palace and star names, năm can chi, a summary line). */
export async function GET(_req: Request, ctx: { params: Promise<{ shareId: string }> }): Promise<Response> {
  const { shareId } = await ctx.params;
  let share;
  try {
    share = await loadPublicShare(shareId);
  } catch (e) {
    if (e instanceof UpstreamError) return plain(503); // already logged by http.ts
    logRefusalOrThrow(e, "share-card"); // logged; a bug rethrows
    return plain(502);
  }
  if (!share) return plain(404);
  const [charm, serif] = await loadFonts();
  const { chart, summary } = share;

  const cells = chart.palaces.map((p) => {
    const { row, col } = cellOf(p.branch);
    return (
      <div
        key={p.key}
        style={{
          position: "absolute",
          left: (col - 1) * CELL_W,
          top: (row - 1) * CELL_H,
          width: CELL_W,
          height: CELL_H,
          display: "flex",
          flexDirection: "column",
          padding: 14,
          border: `2px solid ${GOLD}`,
          background: p.key === "Menh" ? "rgba(168,35,27,0.35)" : "rgba(0,0,0,0.12)",
        }}
      >
        <div style={{ display: "flex", fontFamily: "Noto Serif", fontSize: 24, color: GOLD_SOFT }}>{p.nameVi}</div>
        <div style={{ display: "flex", flexDirection: "column", marginTop: 8 }}>
          {p.major.map((s) => (
            <div key={s.key} style={{ display: "flex", fontFamily: "Noto Serif", fontSize: 26, color: INK, lineHeight: 1.3 }}>
              {s.nameVi}
            </div>
          ))}
        </div>
      </div>
    );
  });

  return new ImageResponse(
    (
      <div
        style={{
          width: W,
          height: H,
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          position: "relative",
          background: "linear-gradient(180deg, #2E100C, #1A0A08)",
          color: INK,
          fontFamily: "Noto Serif",
        }}
      >
        <div style={{ display: "flex", marginTop: 110, fontFamily: "Charm", fontWeight: 700, fontSize: 92, color: GOLD_SOFT }}>
          Tử Vi Đẩu Số
        </div>
        <div style={{ display: "flex", marginTop: 12, fontSize: 30, color: GOLD }}>Lá số tử vi</div>
        <div style={{ display: "flex", position: "absolute", left: BOARD_X, top: BOARD_Y, width: 4 * CELL_W, height: 4 * CELL_H }}>
          {cells}
          <div
            style={{
              position: "absolute",
              left: CELL_W,
              top: CELL_H,
              width: 2 * CELL_W,
              height: 2 * CELL_H,
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "center",
              border: `2px solid ${GOLD}`,
            }}
          >
            <div style={{ display: "flex", fontFamily: "Charm", fontWeight: 700, fontSize: 64, color: GOLD_SOFT }}>
              {`năm ${chart.yearCanChi.nameVi}`}
            </div>
          </div>
        </div>
        <div
          style={{
            display: "flex",
            position: "absolute",
            left: 90,
            top: BOARD_Y + 4 * CELL_H + 70,
            width: W - 180,
            fontSize: 40,
            lineHeight: 1.5,
            color: INK,
          }}
        >
          {summary}
        </div>
        <div style={{ display: "flex", position: "absolute", bottom: 90, fontSize: 32, color: GOLD_SOFT }}>
          Xem lá số của bạn tại Tử Vi Đẩu Số
        </div>
      </div>
    ),
    {
      width: W,
      height: H,
      fonts: [
        { name: "Charm", data: charm, weight: 700, style: "normal" },
        { name: "Noto Serif", data: serif, weight: 400, style: "normal" },
      ],
      headers: { "cache-control": "public, max-age=3600" },
    },
  );
}

import type { CSSProperties } from "react";
import { cellOf } from "@/lib/boardLayout";
import { brightnessLetter, canChi, hourLabel, mutagenLabel } from "@/lib/labels";
import type { Chart, Palace } from "@/lib/types";

function starName(chart: Chart, key: string): string {
  for (const p of chart.palaces) {
    for (const s of [...p.major, ...p.minor]) if (s.key === key) return s.nameVi;
  }
  return key;
}

function lunarLine(c: Chart): string {
  const { day, month, leap } = c.lunar;
  return `ngày ${day} tháng ${month}${leap ? " nhuận" : ""}`;
}

function Cell({ p }: { p: Palace }) {
  const { row, col } = cellOf(p.branch);
  const style: CSSProperties = { gridRow: String(row), gridColumn: String(col) };
  return (
    <a
      href={`#cung-${p.key}`}
      className={`cell${p.key === "Menh" ? " menh" : ""}`}
      style={style}
      aria-label={`Cung ${p.nameVi}, ${canChi(p.stem, p.branch)}`}
    >
      <span className="pn">
        {p.nameVi}
        {p.isBody && <span className="than-mark">Thân</span>}
      </span>
      <span className="stars">
        {p.major.map((s) => (
          <span className="st" key={s.key}>
            {s.nameVi}
            {s.brightness && <b data-brightness={s.brightness}>{brightnessLetter(s.brightness)}</b>}
            {s.mutagen && <i className="hoa">{mutagenLabel(s.mutagen)}</i>}
          </span>
        ))}
      </span>
      {p.minor.length > 0 && (
        <span className="minor">
          {p.minor.map((s, i) => (
            <span key={s.key}>
              {i > 0 && ", "}
              {s.nameVi}
              {s.mutagen && <i className="hoa">{mutagenLabel(s.mutagen)}</i>}
            </span>
          ))}
        </span>
      )}
      {p.adjective.length > 0 && <span className="adj">{p.adjective.map((s) => s.nameVi).join(", ")}</span>}
      {(p.tuan || p.triet) && (
        <span className="marks">
          {p.tuan && <span className="mark-tuan">Tuần</span>}
          {p.triet && <span className="mark-triet">Triệt</span>}
        </span>
      )}
      <span className="chi">{canChi(p.stem, p.branch)}</span>
    </a>
  );
}

export default function Board({ chart }: { chart: Chart }) {
  return (
    <nav className="board" aria-label="Bàn lá số 12 cung">
      {chart.palaces.map((p) => (
        <Cell key={p.key} p={p} />
      ))}
      <div className="center">
        <span className="seal" aria-hidden="true">
          命
        </span>
        <span className="nm">Lá số tử vi</span>
        <span className="meta">
          Âm lịch {lunarLine(chart)}
          <br />
          Năm {chart.yearCanChi.nameVi}, {hourLabel(chart.hourBranch)}
        </span>
        <span className="cuc">
          {chart.cuc.nameVi}
          <br />
          Mệnh chủ {starName(chart, chart.menhChu)}
          <br />
          Thân chủ {starName(chart, chart.thanChu)}
        </span>
      </div>
    </nav>
  );
}

import { levelLabel } from "@/lib/labels";
import type { TodayReading } from "@/lib/types";

const AREA_ORDER = ["CongViec", "DiLai", "TienBac", "SucKhoe"];
const rank = (area: string) => {
  const i = AREA_ORDER.indexOf(area);
  return i === -1 ? AREA_ORDER.length : i;
};

export default function TodayPanel({ today }: { today: TodayReading }) {
  const areas = [...today.areas].sort((a, b) => rank(a.area) - rank(b.area));
  return (
    <section className="today" aria-labelledby="today-title">
      <h2 id="today-title" className="h-display">
        Hôm nay <span className="today-lunar">({today.lunar} âm lịch)</span>
      </h2>
      <p className="today-say">{today.opening}</p>
      <ul className="today-areas">
        {areas.map((a) => (
          <li key={a.area}>
            <span className="area-name">{a.label}</span>
            <span className="level" data-level={a.level}>
              {levelLabel(a.level)}
            </span>
            <span className="area-line">{a.line}</span>
          </li>
        ))}
      </ul>
      <p className="today-say">{today.closing}</p>
    </section>
  );
}

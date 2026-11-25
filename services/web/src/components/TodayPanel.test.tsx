import { describe, expect, it } from "vitest";
import { render } from "@testing-library/react";
import TodayPanel from "@/components/TodayPanel";
import type { TodayReading } from "@/lib/types";

const today: TodayReading = {
  date: "2026-11-25",
  lunar: "15/10",
  dayCanChi: "Giáp Tý",
  character: "LaoThienCo",
  opening: "Hôm nay ta xem nhanh cho con vài điều.",
  areas: [
    { area: "SucKhoe", label: "Sức khỏe", level: "LuuY", line: "Nghỉ ngơi đủ giấc." },
    { area: "CongViec", label: "Công việc", level: "Thuan", line: "Việc gì cũng trôi chảy." },
    { area: "TienBac", label: "Tiền bạc", level: "Binh", line: "Giữ tay, đừng vội." },
    { area: "DiLai", label: "Đi lại", level: "Thuan", line: "Đường đi thuận lợi." },
  ],
  closing: "Thế thôi, con đi làm việc đi.",
};

describe("TodayPanel", () => {
  it("renders the four areas in the fixed order with Vietnamese level labels", () => {
    const { container } = render(<TodayPanel today={today} />);
    const items = Array.from(container.querySelectorAll("li"));
    expect(items).toHaveLength(4);
    expect(items.map((li) => li.querySelector(".area-name")?.textContent)).toEqual([
      "Công việc",
      "Đi lại",
      "Tiền bạc",
      "Sức khỏe",
    ]);
    expect(items.map((li) => li.querySelector(".level")?.textContent)).toEqual(["Thuận", "Thuận", "Bình", "Lưu ý"]);
    expect(items[0].textContent).toContain("Việc gì cũng trôi chảy.");
  });

  it("renders the heading, opening and closing lines", () => {
    const { container } = render(<TodayPanel today={today} />);
    expect(container.querySelector("h2")?.textContent).toContain("Hôm nay");
    expect(container.textContent).toContain("15/10");
    expect(container.textContent).toContain(today.opening);
    expect(container.textContent).toContain(today.closing);
  });
});

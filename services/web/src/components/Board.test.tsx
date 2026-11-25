import { describe, expect, it } from "vitest";
import { render, within } from "@testing-library/react";
import Board from "@/components/Board";
import fixture from "@/test/chart-fixture.json";
import type { Chart } from "@/lib/types";

const chart = fixture as unknown as Chart;

describe("Board", () => {
  it("renders 12 palace links to their reading sections", () => {
    const { container } = render(<Board chart={chart} />);
    const links = container.querySelectorAll("a.cell");
    expect(links).toHaveLength(12);
    for (const p of chart.palaces) {
      expect(container.querySelector(`a[href="#cung-${p.key}"]`)).not.toBeNull();
    }
  });

  it("places palaces on the traditional grid", () => {
    const { container } = render(<Board chart={chart} />);
    const menh = chart.palaces.find((p) => p.key === "Menh")!;
    const el = container.querySelector(`a[href="#cung-Menh"]`) as HTMLElement;
    expect(menh).toBeTruthy();
    expect(el.style.gridRow).toMatch(/^\d/);
    const si = chart.palaces.find((p) => p.branch === "Si")!;
    const siEl = container.querySelector(`a[href="#cung-${si.key}"]`) as HTMLElement;
    expect(siEl.style.gridRow).toBe("1");
    expect(siEl.style.gridColumn).toBe("1");
  });

  it("shows palace name and can chi", () => {
    const { container } = render(<Board chart={chart} />);
    const p = chart.palaces.find((x) => x.key === "ThienDi")!;
    const el = container.querySelector(`a[href="#cung-ThienDi"]`) as HTMLElement;
    expect(within(el).getByText("Thiên Di")).toBeTruthy();
    expect(el.textContent).toContain("Kỷ Tỵ");
    expect(p.stem).toBe("Ji");
  });

  it("shows major stars with brightness letters and mutagen seals", () => {
    const { container } = render(<Board chart={chart} />);
    const el = container.querySelector(`a[href="#cung-ThienDi"]`) as HTMLElement;
    expect(el.textContent).toContain("Vũ Khúc");
    expect(el.querySelector('[data-brightness="Ham"]')?.textContent).toBe("H");
    expect(el.textContent).toContain("Khoa");
    expect(el.textContent).toContain("Quyền");
  });

  it("maps brightness letters", () => {
    const { container } = render(<Board chart={chart} />);
    const letters = new Set(
      Array.from(container.querySelectorAll("[data-brightness]")).map((e) => `${e.getAttribute("data-brightness")}:${e.textContent}`),
    );
    expect(letters.has("Ham:H")).toBe(true);
    expect(letters.has("Dac:Đ")).toBe(true);
  });

  it("outlines Mệnh and marks Thân", () => {
    const { container } = render(<Board chart={chart} />);
    const menh = container.querySelector(`a[href="#cung-Menh"]`) as HTMLElement;
    expect(menh.className).toContain("menh");
    const bodyKey = chart.palaces.find((p) => p.isBody)!.key;
    const body = container.querySelector(`a[href="#cung-${bodyKey}"]`) as HTMLElement;
    expect(within(body).getByText("Thân")).toBeTruthy();
    expect(container.querySelectorAll(".than-mark")).toHaveLength(1);
  });

  it("renders adjective stars in a muted line", () => {
    const { container } = render(<Board chart={chart} />);
    const el = container.querySelector(`a[href="#cung-ThienDi"]`) as HTMLElement;
    expect(el.querySelector(".adj")?.textContent).toContain("Hồng Loan");
  });

  it("marks Tuần and Triệt", () => {
    const withMarks: Chart = {
      ...chart,
      palaces: chart.palaces.map((p, i) => ({ ...p, tuan: i === 0, triet: i === 1 })),
    };
    const { container } = render(<Board chart={withMarks} />);
    expect(container.querySelectorAll(".mark-tuan")).toHaveLength(1);
    expect(container.querySelectorAll(".mark-triet")).toHaveLength(1);
    expect(container.querySelector(".mark-tuan")?.textContent).toBe("Tuần");
    expect(container.querySelector(".mark-triet")?.textContent).toBe("Triệt");
  });

  it("summarises the chart in the centre", () => {
    const { container } = render(<Board chart={chart} />);
    const c = container.querySelector(".center") as HTMLElement;
    expect(c.textContent).toContain("Giáp Tuất");
    expect(c.textContent).toContain("giờ Thìn");
    expect(c.textContent).toContain("Hỏa lục cục");
    expect(c.textContent).toContain("ngày 1 tháng 2");
    expect(c.textContent).toContain("Cự Môn");
    expect(c.textContent).toContain("Văn Xương");
  });
});

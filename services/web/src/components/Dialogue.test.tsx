import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import Dialogue from "@/components/Dialogue";
import fixture from "@/test/reading-fixture.json";
import type { BasicReading } from "@/lib/types";

const reading = fixture as unknown as BasicReading;

function mockMotion(reduce: boolean) {
  window.matchMedia = vi.fn((q: string) => ({
    matches: reduce && q.includes("prefers-reduced-motion"),
    media: q,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  })) as unknown as typeof window.matchMedia;
}
const words = (c: HTMLElement) => Array.from(c.querySelectorAll<HTMLElement>(".w"));
const seal = () => screen.queryByRole("img", { name: "Ấn son" });
const showAll = () => screen.queryByRole("button", { name: "Hiện hết" });

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("Dialogue with reduced motion", () => {
  beforeEach(() => mockMotion(true));

  it("shows every title and line at once, plus the seal and the disclaimer", () => {
    const { container } = render(<Dialogue sections={reading.sections} />);
    const text = (container.textContent ?? "").replace(/\s+/g, " ");
    for (const s of reading.sections) {
      if (s.title) expect(text).toContain(s.title);
      for (const l of s.lines) expect(text).toContain(l);
    }
    expect(words(container).every((w) => w.dataset.on === "true")).toBe(true);
    expect(seal()).not.toBeNull();
    expect(showAll()).toBeNull();
    expect(container.querySelector("#disclaimer")?.textContent).toContain("tham khảo");
  });

  it("gives sections the ids the board links to", () => {
    const { container } = render(<Dialogue sections={reading.sections} />);
    const ids = ["menh", "than", "cuc", "cung-PhuMau", "cung-QuanLoc", "cung-ThienDi", "cung-HuynhDe"];
    for (const id of ids) expect(container.querySelector(`section#${id}`), id).not.toBeNull();
    expect(container.querySelector("#cung-Menh")).toBeNull();
  });

  it("renders a section without a heading when its title is omitted", () => {
    const { container } = render(<Dialogue sections={reading.sections} />);
    expect(container.querySelector("#greeting h2")).toBeNull();
    expect(container.querySelector("#menh h2")?.textContent).toContain("Cung Mệnh");
  });

  it("puts the disclaimer after the seal", () => {
    const { container } = render(<Dialogue sections={reading.sections} />);
    const s = container.querySelector(".seal-stamp")!;
    const d = container.querySelector("#disclaimer")!;
    expect(s.compareDocumentPosition(d) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});

describe("Dialogue animating", () => {
  beforeEach(() => {
    mockMotion(false);
    vi.useFakeTimers();
    Element.prototype.scrollIntoView = vi.fn();
  });

  it("starts hidden, reveals progressively and stamps the seal at the end", () => {
    const { container } = render(<Dialogue sections={reading.sections} />);
    expect(words(container).every((w) => w.dataset.on === "false")).toBe(true);
    expect(seal()).toBeNull();
    expect(container.querySelector("#disclaimer")).toBeNull();
    expect(showAll()).not.toBeNull();

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    const on = words(container).filter((w) => w.dataset.on === "true").length;
    expect(on).toBeGreaterThan(0);
    expect(on).toBeLessThan(words(container).length);
    expect(seal()).toBeNull();

    act(() => {
      vi.advanceTimersByTime(10 * 60 * 1000);
    });
    expect(words(container).every((w) => w.dataset.on === "true")).toBe(true);
    expect(seal()).not.toBeNull();
    expect(showAll()).toBeNull();
  });

  it("Hiện hết reveals all lines and the seal immediately", () => {
    const { container } = render(<Dialogue sections={reading.sections} />);
    act(() => {
      vi.advanceTimersByTime(300);
    });
    fireEvent.click(showAll()!);
    expect(words(container).every((w) => w.dataset.on === "true")).toBe(true);
    expect(seal()).not.toBeNull();
    expect(showAll()).toBeNull();
  });

  it("follows the line being revealed", () => {
    render(<Dialogue sections={reading.sections} />);
    act(() => {
      vi.advanceTimersByTime(3000);
    });
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith({ block: "nearest" });
  });
});

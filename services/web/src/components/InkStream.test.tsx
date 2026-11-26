import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { InkStream } from "./InkStream";

function motion(reduce: boolean) {
  vi.stubGlobal("matchMedia", (q: string) => ({
    matches: reduce && q.includes("prefers-reduced-motion"),
    media: q,
    addEventListener: () => {},
    removeEventListener: () => {},
  }));
}
const on = (c: HTMLElement) => c.querySelectorAll('.w[data-on="true"]').length;
const all = (c: HTMLElement) => c.querySelectorAll(".w").length;

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("InkStream", () => {
  it("shows every word at once under reduced motion, and the seal when done", () => {
    motion(true);
    const { container } = render(<InkStream sentences={["Một hai ba. ", "Bốn năm sáu."]} done />);
    expect(all(container)).toBe(6);
    expect(on(container)).toBe(6);
    expect(screen.getByRole("img", { name: "Ấn son" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Hiện hết" })).toBeNull();
  });

  it("inks words in at 60 ms each", async () => {
    motion(false);
    const { container } = render(<InkStream sentences={["Một hai ba bốn năm."]} done={false} />);
    expect(on(container)).toBe(0);
    await act(() => vi.advanceTimersByTimeAsync(60));
    expect(on(container)).toBe(1);
    await act(() => vi.advanceTimersByTimeAsync(120));
    expect(on(container)).toBe(3);
  });

  it("keeps inking words of sentences that arrive later", async () => {
    motion(false);
    const { container, rerender } = render(<InkStream sentences={["Một hai."]} done={false} />);
    await act(() => vi.advanceTimersByTimeAsync(600));
    expect(on(container)).toBe(2);
    rerender(<InkStream sentences={["Một hai. ", "Ba bốn."]} done={false} />);
    expect(on(container)).toBe(2);
    await act(() => vi.advanceTimersByTimeAsync(120));
    expect(on(container)).toBe(4);
  });

  it("'Hiện hết' reveals everything and then the seal; no seal before that", () => {
    motion(false);
    const { container } = render(<InkStream sentences={["Một hai ba bốn năm sáu bảy tám."]} done />);
    expect(screen.queryByRole("img", { name: "Ấn son" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Hiện hết" }));
    expect(on(container)).toBe(8);
    expect(screen.getByRole("img", { name: "Ấn son" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Hiện hết" })).toBeNull();
  });

  it("shows no seal while the stream is not done, even when every word is visible", () => {
    motion(true);
    render(<InkStream sentences={["Một hai."]} done={false} />);
    expect(screen.queryByRole("img", { name: "Ấn son" })).toBeNull();
  });

  it("scrolls the newest words into view", async () => {
    motion(false);
    const scroll = vi.fn();
    Element.prototype.scrollIntoView = scroll;
    render(<InkStream sentences={["Một hai ba."]} done={false} />);
    await act(() => vi.advanceTimersByTimeAsync(120));
    expect(scroll).toHaveBeenCalledWith({ block: "nearest" });
  });
});

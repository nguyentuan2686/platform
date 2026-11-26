import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen } from "@testing-library/react";

const refresh = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));

import { ReadingStatusPoller } from "./ReadingStatusPoller";

const RID = "33333333-3333-4333-8333-333333333333";
let fetchMock: ReturnType<typeof vi.fn>;
let statuses: string[];
let hidden = false;
const ok = (status: string) => new Response(JSON.stringify({ status, follow_ups_left: 0 }), { status: 200 });
const polls = () => fetchMock.mock.calls.filter((c) => String(c[0]) === `/api/qa/readings/${RID}`);

beforeEach(() => {
  vi.useFakeTimers();
  hidden = false;
  statuses = [];
  refresh.mockReset();
  Object.defineProperty(document, "visibilityState", { configurable: true, get: () => (hidden ? "hidden" : "visible") });
  fetchMock = vi.fn(async () => ok(statuses.shift() ?? "RETRYING"));
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("ReadingStatusPoller", () => {
  it("polls every 15 s and refreshes the page once the status changes", async () => {
    statuses = ["RETRYING", "ANSWERED"];
    render(<ReadingStatusPoller readingId={RID} status="RETRYING" />);
    expect(screen.getByRole("status").getAttribute("aria-live")).toBe("polite");
    await act(() => vi.advanceTimersByTimeAsync(14_999));
    expect(polls()).toHaveLength(0);
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(polls()).toHaveLength(1);
    expect(refresh).not.toHaveBeenCalled();
    await act(() => vi.advanceTimersByTimeAsync(15_000));
    expect(polls()).toHaveLength(2);
    expect(refresh).toHaveBeenCalledTimes(1);
    await act(() => vi.advanceTimersByTimeAsync(60_000)); // stopped after the change
    expect(polls()).toHaveLength(2);
  });

  it("does not poll while the tab is hidden, and resumes when visible", async () => {
    hidden = true;
    render(<ReadingStatusPoller readingId={RID} status="RETRYING" />);
    await act(() => vi.advanceTimersByTimeAsync(45_000));
    expect(polls()).toHaveLength(0);
    hidden = false;
    await act(() => vi.advanceTimersByTimeAsync(15_000));
    expect(polls()).toHaveLength(1);
  });

  it("keeps polling after a failed check", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("offline"));
    render(<ReadingStatusPoller readingId={RID} status="RETRYING" />);
    await act(() => vi.advanceTimersByTimeAsync(30_000));
    expect(polls()).toHaveLength(2);
    expect(refresh).not.toHaveBeenCalled();
  });

  it.each([401, 404])("stops polling on %i and refreshes the page once", async (code) => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ reason: "x" }), { status: code }));
    render(<ReadingStatusPoller readingId={RID} status="RETRYING" />);
    await act(() => vi.advanceTimersByTimeAsync(90_000));
    expect(polls()).toHaveLength(1);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("ignores a non-OK answer (the route has logged it) and stops on unmount", async () => {
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 503 }));
    const { unmount } = render(<ReadingStatusPoller readingId={RID} status="RETRYING" />);
    await act(() => vi.advanceTimersByTimeAsync(15_000));
    expect(refresh).not.toHaveBeenCalled();
    unmount();
    await act(() => vi.advanceTimersByTimeAsync(60_000));
    expect(polls()).toHaveLength(1);
  });
});

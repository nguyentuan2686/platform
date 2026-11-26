import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";

const refresh = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));

import { OrderStatus } from "./OrderStatus";

const ORD = "22222222-2222-2222-2222-222222222222";
const PAID_TEXT = "Đã nhận tiền. Lượt của con đã được cộng.";
const LATE_TEXT = /Đơn đã quá 30 phút\. Nếu con đã chuyển đúng nội dung/;

let statuses: string[];
let fetchMock: ReturnType<typeof vi.fn>;
let hidden = false;

const ok = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const orderFetches = () => fetchMock.mock.calls.filter((c) => String(c[0]) === `/api/orders/${ORD}`);

beforeEach(() => {
  vi.useFakeTimers();
  statuses = [];
  hidden = false;
  refresh.mockReset();
  Object.defineProperty(document, "visibilityState", { configurable: true, get: () => (hidden ? "hidden" : "visible") });
  fetchMock = vi.fn(async (url: string) => {
    if (url === "/api/dev/sepay") return new Response(null, { status: 204 });
    return ok({ status: statuses.shift() ?? "PENDING" });
  });
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const expiresAt = () => new Date(Date.now() + 30 * 60_000).toISOString();

describe("OrderStatus", () => {
  it("polls every 3 s while PENDING; on PAID it stops, announces and refreshes once", async () => {
    statuses = ["PENDING", "PAID"];
    render(<OrderStatus orderId={ORD} initial="PENDING" expiresAt={expiresAt()} />);
    await act(() => vi.advanceTimersByTimeAsync(2999));
    expect(orderFetches()).toHaveLength(0);
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(orderFetches()).toHaveLength(1);
    await act(() => vi.advanceTimersByTimeAsync(3000));
    expect(orderFetches()).toHaveLength(2);
    expect(screen.getByText(PAID_TEXT)).toBeTruthy();
    expect(screen.getByText(PAID_TEXT).closest("[aria-live]")?.getAttribute("aria-live")).toBe("polite");
    expect(screen.getByRole("link", { name: "Hỏi thầy ngay" }).getAttribute("href")).toBe("/vi/hoi");
    expect(refresh).toHaveBeenCalledTimes(1);
    await act(() => vi.advanceTimersByTimeAsync(60_000));
    expect(orderFetches()).toHaveLength(2);
    // then 3 follow-up refreshes (2, 5, 10 s) while the async credit grant lands; nothing after that
    expect(refresh).toHaveBeenCalledTimes(4);
  });

  it("does not fetch while the tab is hidden, and resumes when it is visible again", async () => {
    hidden = true;
    render(<OrderStatus orderId={ORD} initial="PENDING" expiresAt={expiresAt()} />);
    await act(() => vi.advanceTimersByTimeAsync(30_000));
    expect(orderFetches()).toHaveLength(0);
    statuses = ["PAID"];
    hidden = false;
    await act(async () => {
      document.dispatchEvent(new Event("visibilitychange"));
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(orderFetches()).toHaveLength(1);
    expect(screen.getByText(PAID_TEXT)).toBeTruthy();
  });

  it("on EXPIRED shows the late-payment note and keeps checking every 30 s", async () => {
    statuses = ["EXPIRED", "EXPIRED", "PAID"];
    render(<OrderStatus orderId={ORD} initial="PENDING" expiresAt={new Date(Date.now() + 1000).toISOString()} />);
    await act(() => vi.advanceTimersByTimeAsync(3000));
    expect(screen.getByText(LATE_TEXT)).toBeTruthy();
    expect(screen.getByText(/quá 24 giờ chưa thấy, ta sẽ đối soát thủ công\./)).toBeTruthy();
    await act(() => vi.advanceTimersByTimeAsync(29_999));
    expect(orderFetches()).toHaveLength(1);
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(orderFetches()).toHaveLength(2);
    await act(() => vi.advanceTimersByTimeAsync(30_000));
    expect(screen.getByText(PAID_TEXT)).toBeTruthy();
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("stops checking an EXPIRED order 24 h after expiry", async () => {
    const longAgo = new Date(Date.now() - 25 * 3600_000).toISOString();
    render(<OrderStatus orderId={ORD} initial="EXPIRED" expiresAt={longAgo} />);
    expect(screen.getByText(LATE_TEXT)).toBeTruthy();
    await act(() => vi.advanceTimersByTimeAsync(120_000));
    expect(orderFetches()).toHaveLength(0);
  });

  it("does not poll or refresh an order already PAID", async () => {
    render(<OrderStatus orderId={ORD} initial="PAID" expiresAt={expiresAt()} />);
    expect(screen.getByText(PAID_TEXT)).toBeTruthy();
    await act(() => vi.advanceTimersByTimeAsync(10_000));
    expect(fetchMock).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
  });

  it("keeps polling through a failed check", async () => {
    fetchMock.mockImplementationOnce(async () => {
      throw new TypeError("offline");
    });
    statuses = ["PAID"];
    render(<OrderStatus orderId={ORD} initial="PENDING" expiresAt={expiresAt()} />);
    await act(() => vi.advanceTimersByTimeAsync(3000));
    await act(() => vi.advanceTimersByTimeAsync(3000));
    expect(screen.getByText(PAID_TEXT)).toBeTruthy();
  });

  it("the simulator button posts the order id and checks at once", async () => {
    statuses = ["PAID"];
    render(<OrderStatus orderId={ORD} initial="PENDING" expiresAt={expiresAt()} simulator />);
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Giả lập chuyển khoản (môi trường thử)" }));
      await vi.advanceTimersByTimeAsync(0);
    });
    const sim = fetchMock.mock.calls.find((c) => c[0] === "/api/dev/sepay");
    expect(sim?.[1]).toMatchObject({ method: "POST", body: JSON.stringify({ order_id: ORD }) });
    expect(screen.getByText(PAID_TEXT)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Giả lập/ })).toBeNull();
  });

  it("has no simulator button unless enabled", () => {
    render(<OrderStatus orderId={ORD} initial="PENDING" expiresAt={expiresAt()} />);
    expect(screen.queryByRole("button", { name: /Giả lập/ })).toBeNull();
  });
});

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render } from "@testing-library/react";

const getViewer = vi.hoisted(() => vi.fn());
vi.mock("@/lib/session", () => ({ getViewer }));

import { BalanceChip } from "./BalanceChip";
import { UpstreamError, UpstreamProblem } from "@/lib/http";

const account = (entitlements: unknown) => ({
  kind: "account",
  accountId: "a",
  emailMasked: "t***@e***.com",
  token: "t",
  isAdmin: false,
  entitlements,
});
const future = () => new Date(Date.now() + 86400_000).toISOString();
const past = () => new Date(Date.now() - 86400_000).toISOString();

// No mockClear/mockReset between tests: with vitest 5 they make the rejecting mock below fail the test
// even though BalanceChip catches it. Every test sets its own implementation.
afterEach(cleanup);

async function text(): Promise<string> {
  const el = await BalanceChip();
  if (el === null) return "";
  return render(el).container.textContent ?? "";
}

describe("BalanceChip", () => {
  it("shows the credits of an account", async () => {
    getViewer.mockResolvedValue(account({ credits: 3, pass: null, features: [] }));
    expect(await text()).toBe("Còn 3 lượt");
  });

  it("shows an active pass instead of credits", async () => {
    getViewer.mockResolvedValue(account({ credits: 3, pass: { kind: "MONTH", ends_at: future(), questions_left: 2 }, features: [] }));
    expect(await text()).toBe("Thẻ tháng");
    getViewer.mockResolvedValue(account({ credits: 0, pass: { kind: "YEAR", ends_at: future(), questions_left: null }, features: [] }));
    expect(await text()).toBe("Thẻ năm");
  });

  it("ignores an expired pass", async () => {
    getViewer.mockResolvedValue(account({ credits: 0, pass: { kind: "YEAR", ends_at: past(), questions_left: null }, features: [] }));
    expect(await text()).toBe("Còn 0 lượt");
  });

  it("links guests and anonymous viewers to the login page", async () => {
    for (const v of [{ kind: "guest", guestId: "g", token: "t" }, { kind: "anon" }]) {
      getViewer.mockResolvedValue(v);
      const el = await BalanceChip();
      const { container, unmount } = render(el as React.ReactElement);
      const a = container.querySelector("a");
      expect(a?.textContent).toBe("Đăng nhập");
      expect(a?.getAttribute("href")).toBe("/vi/dang-nhap");
      unmount();
    }
  });

  it("renders nothing when identity is down (http.ts already logged it)", async () => {
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    getViewer.mockImplementation(async () => {
      throw new UpstreamError();
    });
    expect(await BalanceChip()).toBeNull();
    expect(write).not.toHaveBeenCalled();
    write.mockRestore();
  });

  it("treats a 401 as anonymous without an error log", async () => {
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    getViewer.mockImplementation(async () => {
      throw new UpstreamProblem(401, undefined, "identity-svc");
    });
    expect(await BalanceChip()).toBeNull();
    expect(write).not.toHaveBeenCalled();
    write.mockRestore();
  });

  it("logs a 403 (e.g. wrong internal token) with no PII and hides the chip", async () => {
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    getViewer.mockImplementation(async () => {
      throw new UpstreamProblem(403, undefined, "identity-svc");
    });
    expect(await BalanceChip()).toBeNull();
    const out = write.mock.calls.map((c) => String(c[0])).join("");
    write.mockRestore();
    expect(JSON.parse(out)).toMatchObject({ level: "warn", upstream: "identity-svc", status: "403", part: "balance-chip" });
    expect(out).not.toMatch(/Bearer|token|@/);
  });

  it("rethrows a bug instead of hiding it", async () => {
    getViewer.mockImplementation(async () => {
      throw new TypeError("boom");
    });
    await expect(BalanceChip()).rejects.toThrow("boom");
  });
});

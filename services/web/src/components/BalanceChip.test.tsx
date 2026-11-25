import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render } from "@testing-library/react";

const getViewer = vi.hoisted(() => vi.fn());
vi.mock("@/lib/session", () => ({ getViewer }));

import { BalanceChip } from "./BalanceChip";
import { UpstreamError } from "@/lib/http";

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

  it("renders nothing for guests and anonymous viewers", async () => {
    getViewer.mockResolvedValue({ kind: "guest", guestId: "g", token: "t" });
    expect(await text()).toBe("");
    getViewer.mockResolvedValue({ kind: "anon" });
    expect(await text()).toBe("");
  });

  it("renders nothing when identity is down", async () => {
    getViewer.mockImplementation(async () => {
      throw new UpstreamError();
    });
    expect(await BalanceChip()).toBeNull();
  });
});

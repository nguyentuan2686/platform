import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render } from "@testing-library/react";

const getViewer = vi.hoisted(() => vi.fn());
vi.mock("@/lib/session", () => ({ getViewer }));

import { PassBanner } from "./PassBanner";
import { UpstreamError } from "@/lib/http";

const account = (pass: unknown) => ({
  kind: "account",
  accountId: "a",
  emailMasked: "",
  token: "t",
  isAdmin: false,
  entitlements: { credits: 0, pass, features: [] },
});

afterEach(cleanup);

describe("PassBanner", () => {
  it("reminds 3 days before a month pass ends, linking to /vi/mua", async () => {
    const ends = new Date(Date.now() + 36 * 3600_000).toISOString();
    getViewer.mockResolvedValue(account({ kind: "MONTH", ends_at: ends, questions_left: 1 }));
    const el = await PassBanner();
    const { container, getByRole } = render(el!);
    expect(container.textContent).toMatch(/^Thẻ tháng của con còn [12] ngày \(đến \d{2}\/\d{2}\/\d{4}\)\. Gia hạn$/);
    expect(getByRole("link", { name: "Gia hạn" }).getAttribute("href")).toBe("/vi/mua");
  });

  it("names a year pass", async () => {
    const ends = new Date(Date.now() + 3600_000).toISOString();
    getViewer.mockResolvedValue(account({ kind: "YEAR", ends_at: ends, questions_left: null }));
    const { container } = render((await PassBanner())!);
    expect(container.textContent).toMatch(/^Thẻ năm của con còn 1 ngày/);
  });

  it("is absent far from the end, without a pass, for guests and when identity is down", async () => {
    getViewer.mockResolvedValue(account({ kind: "MONTH", ends_at: new Date(Date.now() + 10 * 86400_000).toISOString(), questions_left: 2 }));
    expect(await PassBanner()).toBeNull();
    getViewer.mockResolvedValue(account(null));
    expect(await PassBanner()).toBeNull();
    getViewer.mockResolvedValue({ kind: "guest", guestId: "g", token: "t" });
    expect(await PassBanner()).toBeNull();
    getViewer.mockRejectedValue(new UpstreamError());
    expect(await PassBanner()).toBeNull();
  });
});

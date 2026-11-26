import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

const m = vi.hoisted(() => ({ getViewer: vi.fn(), getProfile: vi.fn() }));
vi.mock("@/lib/session", () => ({ getViewer: () => m.getViewer() }));
vi.mock("@/lib/identity", () => ({ getProfile: (b: string) => m.getProfile(b) }));
vi.mock("@/app/vi/actions", () => ({ submitBirth: vi.fn() }));

import Home from "@/app/vi/page";
import { UpstreamError, UpstreamProblem } from "@/lib/http";

afterEach(cleanup);
beforeEach(() => {
  m.getViewer.mockReset();
  m.getProfile.mockReset();
});

describe("/vi", () => {
  it("shows the 'Xem lá số của con' link above the form when a profile exists", async () => {
    m.getViewer.mockResolvedValue({ kind: "guest", guestId: "g", token: "t" });
    m.getProfile.mockResolvedValue({ id: "p" });
    render(await Home());
    const link = screen.getByRole("link", { name: "Xem lá số của con" });
    expect(link.getAttribute("href")).toBe("/vi/la-so");
    const form = document.querySelector("form")!;
    expect(link.compareDocumentPosition(form) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("hides the link without a profile, for anonymous viewers and when identity is down", async () => {
    m.getViewer.mockResolvedValue({ kind: "guest", guestId: "g", token: "t" });
    m.getProfile.mockResolvedValue(null);
    render(await Home());
    expect(screen.queryByRole("link", { name: "Xem lá số của con" })).toBeNull();
    cleanup();
    m.getViewer.mockResolvedValue({ kind: "anon" });
    render(await Home());
    expect(screen.queryByRole("link", { name: "Xem lá số của con" })).toBeNull();
    expect(m.getProfile).toHaveBeenCalledTimes(1);
    cleanup();
    m.getViewer.mockRejectedValue(new UpstreamError());
    render(await Home());
    expect(screen.queryByRole("link", { name: "Xem lá số của con" })).toBeNull();
  });
  it("hides the link on a 401 with no log, logs a 403 and hides it, rethrows a bug", async () => {
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    m.getViewer.mockResolvedValue({ kind: "guest", guestId: "g", token: "t" });
    m.getProfile.mockRejectedValue(new UpstreamProblem(401, undefined, "identity-svc"));
    render(await Home());
    expect(screen.queryByRole("link", { name: "Xem lá số của con" })).toBeNull();
    expect(write).not.toHaveBeenCalled();
    cleanup();
    m.getProfile.mockRejectedValue(new UpstreamProblem(403, undefined, "identity-svc"));
    render(await Home());
    expect(screen.queryByRole("link", { name: "Xem lá số của con" })).toBeNull();
    const out = write.mock.calls.map((c) => String(c[0])).join("");
    write.mockRestore();
    expect(JSON.parse(out)).toMatchObject({ upstream: "identity-svc", status: "403", part: "saved-chart-link" });
    m.getProfile.mockRejectedValue(new TypeError("boom"));
    await expect(Home()).rejects.toThrow("boom");
  });
});

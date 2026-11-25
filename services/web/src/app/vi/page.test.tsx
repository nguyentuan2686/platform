import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

const m = vi.hoisted(() => ({ getViewer: vi.fn(), getProfile: vi.fn() }));
vi.mock("@/lib/session", () => ({ getViewer: () => m.getViewer() }));
vi.mock("@/lib/identity", () => ({ getProfile: (b: string) => m.getProfile(b) }));
vi.mock("@/app/vi/actions", () => ({ submitBirth: vi.fn() }));

import Home from "@/app/vi/page";
import { UpstreamError } from "@/lib/http";

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
});

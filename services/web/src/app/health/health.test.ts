import { describe, expect, it, vi } from "vitest";
import { GET as live } from "@/app/health/live/route";
import { GET as ready } from "@/app/health/ready/route";

const redirect = vi.fn();
vi.mock("next/navigation", () => ({
  redirect: (url: string) => redirect(url),
}));

describe("health routes", () => {
  it.each([
    ["live", live],
    ["ready", ready],
  ])("%s returns 200 UP", async (_n, handler) => {
    const res = handler();
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ status: "UP" });
  });
});

describe("root page", () => {
  it("redirects / to /vi", async () => {
    const { default: Root } = await import("@/app/page");
    Root();
    expect(redirect).toHaveBeenCalledWith("/vi");
  });
});

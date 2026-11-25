import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen } from "@testing-library/react";

const pathname = vi.hoisted(() => ({ value: "/vi/la-so" }));
vi.mock("next/navigation", () => ({ usePathname: () => pathname.value }));

import { TabBar } from "./TabBar";

afterEach(() => {
  cleanup();
  window.location.hash = "";
});
beforeEach(() => {
  pathname.value = "/vi/la-so";
});

describe("TabBar", () => {
  it("ships Hôm nay, Lá số and Tài khoản with their links", () => {
    render(<TabBar />);
    const nav = screen.getByRole("navigation");
    const links = screen.getAllByRole("link");
    expect(nav).toBeTruthy();
    expect(links.map((l) => l.textContent)).toEqual(["今Hôm nay", "命Lá số", "我Tài khoản"]);
    expect(links.map((l) => l.getAttribute("href"))).toEqual(["/vi/la-so#hom-nay", "/vi/la-so", "/vi/tai-khoan"]);
  });

  it("marks Tài khoản current on /vi/tai-khoan", () => {
    pathname.value = "/vi/tai-khoan";
    render(<TabBar />);
    expect(screen.getByRole("link", { name: /Tài khoản/ }).getAttribute("aria-current")).toBe("page");
    expect(screen.getByRole("link", { name: /Lá số/ }).getAttribute("aria-current")).toBeNull();
  });

  it("marks Lá số current on /vi/la-so", () => {
    render(<TabBar />);
    expect(screen.getByRole("link", { name: /Lá số/ }).getAttribute("aria-current")).toBe("page");
    expect(screen.getByRole("link", { name: /Hôm nay/ }).getAttribute("aria-current")).toBeNull();
  });

  it("marks Hôm nay current on the #hom-nay anchor", () => {
    window.location.hash = "#hom-nay";
    render(<TabBar />);
    expect(screen.getByRole("link", { name: /Hôm nay/ }).getAttribute("aria-current")).toBe("page");
    expect(screen.getByRole("link", { name: /Lá số/ }).getAttribute("aria-current")).toBeNull();
  });

  it("follows hash changes between the two tabs", () => {
    render(<TabBar />);
    act(() => {
      window.location.hash = "#hom-nay";
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    });
    expect(screen.getByRole("link", { name: /Hôm nay/ }).getAttribute("aria-current")).toBe("page");
  });

  it("marks nothing on other pages", () => {
    pathname.value = "/vi";
    render(<TabBar />);
    for (const l of screen.getAllByRole("link")) expect(l.getAttribute("aria-current")).toBeNull();
  });
});

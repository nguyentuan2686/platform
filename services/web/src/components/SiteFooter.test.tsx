import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { SiteFooter } from "./SiteFooter";

afterEach(cleanup);

describe("SiteFooter", () => {
  it("keeps the disclaimer and links the knowledge section, Tử vi 2027, Xem số hợp mệnh and the three legal pages", () => {
    render(<SiteFooter />);
    expect(screen.getByText(/tham khảo văn hóa và giải trí/)).toBeTruthy();
    const hrefs = screen.getAllByRole("link").map((a) => a.getAttribute("href"));
    expect(hrefs).toEqual(["/vi/kien-thuc", "/vi/tu-vi-2027", "/vi/xem-so", "/vi/chinh-sach-rieng-tu", "/vi/dieu-khoan", "/vi/hoan-tien"]);
  });
});

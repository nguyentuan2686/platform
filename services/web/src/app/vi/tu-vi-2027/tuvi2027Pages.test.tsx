import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import Year, { metadata } from "@/app/vi/tu-vi-2027/page";
import ZodiacPage, { generateStaticParams } from "@/app/vi/tu-vi-2027/[slug]/page";

afterEach(cleanup);

describe("/vi/tu-vi-2027", () => {
  it("is an indexable article page linking to the 12 signs and to the free chart", () => {
    expect(metadata.robots).toMatchObject({ index: true });
    render(<Year />);
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Tử vi 2027 Đinh Mùi cho 12 con giáp");
    const hrefs = screen.getAllByRole("link").map((a) => a.getAttribute("href"));
    expect(hrefs.filter((h) => h?.startsWith("/vi/tu-vi-2027/tuoi-"))).toHaveLength(12);
    expect(screen.getByRole("link", { name: /Lập lá số miễn phí/ }).getAttribute("href")).toBe("/vi");
  });
});

describe("/vi/tu-vi-2027/[slug]", () => {
  it("pre-renders the 12 signs", () => {
    expect(generateStaticParams()).toHaveLength(12);
  });

  it("shows the six sections, birth years, the chart call to action and the 11 other signs", async () => {
    render(await ZodiacPage({ params: Promise.resolve({ slug: "tuoi-mui" }) }));
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Tử vi 2027 tuổi Mùi (năm Đinh Mùi)");
    for (const h of ["Tổng quan", "Công việc", "Tiền bạc", "Tình cảm", "Sức khỏe", "Lời khuyên của Lão Thiên Cơ"]) {
      expect(screen.getByRole("heading", { level: 2, name: h })).toBeTruthy();
    }
    expect(document.body.textContent).toContain("Năm tuổi");
    expect(document.body.textContent).toContain("1991, 2003, 2015");
    expect(screen.getByRole("link", { name: /Lập lá số miễn phí/ }).getAttribute("href")).toBe("/vi");
    expect(screen.getAllByRole("link", { name: /^Tuổi / })).toHaveLength(11);
  });
});

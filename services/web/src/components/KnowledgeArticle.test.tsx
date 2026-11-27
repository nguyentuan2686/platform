import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { KnowledgeArticle, knowledgeTitle } from "@/components/KnowledgeArticle";
import NotFound from "@/app/not-found";

afterEach(cleanup);

const O = "https://tuvi.example";
const page = { key: "ThienCo", slug: "thien-co", name: "Thiên Cơ", content: "Đoạn một.\n\nĐoạn hai." };
const other = { key: "TuVi", slug: "tu-vi", name: "Tử Vi", content: "x" };

const ldBlocks = (c: HTMLElement) =>
  [...c.querySelectorAll('script[type="application/ld+json"]')].map((s) => JSON.parse(s.textContent ?? "") as Record<string, unknown>);

describe("KnowledgeArticle", () => {
  it("renders the breadcrumb, the text and sibling links", () => {
    render(<KnowledgeArticle origin={O} kind="sao" page={page} others={[other]} />);
    const crumbs = screen.getByRole("navigation", { name: "Đường dẫn" });
    expect([...crumbs.querySelectorAll("a")].map((a) => a.getAttribute("href"))).toEqual(["/vi", "/vi/kien-thuc"]);
    expect(crumbs.querySelector('[aria-current="page"]')?.textContent).toBe("Sao Thiên Cơ");
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Thiên Cơ");
    expect(screen.getByText("Đoạn hai.")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Tử Vi" }).getAttribute("href")).toBe("/vi/kien-thuc/sao/tu-vi");
  });

  it("emits BreadcrumbList and Article JSON-LD matching the visible page", () => {
    const { container } = render(<KnowledgeArticle origin={O} kind="cung" page={page} others={[]} />);
    const [crumbs, article] = ldBlocks(container);
    expect(crumbs).toMatchObject({ "@type": "BreadcrumbList" });
    expect((crumbs!.itemListElement as { name: string; item: string }[]).map((i) => i.item)).toEqual([
      `${O}/vi`,
      `${O}/vi/kien-thuc`,
      `${O}/vi/kien-thuc/cung/thien-co`,
    ]);
    expect(article).toMatchObject({
      "@type": "Article",
      headline: knowledgeTitle("cung", page),
      mainEntityOfPage: `${O}/vi/kien-thuc/cung/thien-co`,
    });
  });
});

describe("root not-found", () => {
  it("is Vietnamese and links home and the knowledge section", () => {
    render(<NotFound />);
    expect(screen.getByRole("heading", { level: 1 }).textContent).toContain("không tìm thấy");
    const hrefs = screen.getAllByRole("link").map((a) => a.getAttribute("href"));
    expect(hrefs).toEqual(["/vi", "/vi/kien-thuc"]);
  });
});

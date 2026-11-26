import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import type { FormState } from "@/app/vi/actions";

vi.mock("@/app/vi/actions", () => ({ submitBirth: vi.fn() }));

import { BirthForm } from "./BirthForm";

afterEach(cleanup);

describe("BirthForm", () => {
  it("renders labelled fields, zone list defaulting to Việt Nam, and the submit button", () => {
    render(<BirthForm />);
    expect(screen.getByLabelText("Ngày sinh (dương lịch)")).toHaveProperty("type", "date");
    expect(screen.getByLabelText("Giờ sinh")).toHaveProperty("type", "time");
    expect(screen.getByLabelText("Nam")).toHaveProperty("type", "radio");
    expect(screen.getByLabelText("Nữ")).toHaveProperty("type", "radio");
    const zone = screen.getByLabelText("Nơi sinh") as HTMLSelectElement;
    expect(zone.value).toBe("Asia/Ho_Chi_Minh");
    expect(within(zone).getByRole("option", { name: "Việt Nam" })).toBeTruthy();
    expect(within(zone).getAllByRole("option").length).toBeGreaterThan(5);
    expect(screen.getByRole("button", { name: "Xin Lão Thiên Cơ xem giúp" })).toBeTruthy();
  });

  it("shows server errors under the matching field", () => {
    const state: FormState = { errors: { date: "Ngày sai.", time: "Giờ sai.", gender: "Chọn giới tính.", zone: "Nơi sai." } };
    render(<BirthForm initialState={state} />);
    for (const [label, msg] of [
      ["Ngày sinh (dương lịch)", "Ngày sai."],
      ["Giờ sinh", "Giờ sai."],
      ["Nơi sinh", "Nơi sai."],
    ] as const) {
      const input = screen.getByLabelText(label);
      const err = screen.getByText(msg);
      expect(input.getAttribute("aria-describedby")).toBe(err.id);
      expect(input.getAttribute("aria-invalid")).toBe("true");
    }
    expect(screen.getByText("Chọn giới tính.")).toBeTruthy();
  });

  it("keeps submitted values after a failed submit", () => {
    const state: FormState = {
      errors: { date: "Ngày sai." },
      values: { date: "1994-03-12", time: "08:30", gender: "female", zone: "Asia/Tokyo" },
    };
    render(<BirthForm initialState={state} />);
    expect((screen.getByLabelText("Ngày sinh (dương lịch)") as HTMLInputElement).value).toBe("1994-03-12");
    expect((screen.getByLabelText("Giờ sinh") as HTMLInputElement).value).toBe("08:30");
    expect((screen.getByLabelText("Nữ") as HTMLInputElement).checked).toBe(true);
    expect((screen.getByLabelText("Nam") as HTMLInputElement).checked).toBe(false);
    expect((screen.getByLabelText("Nơi sinh") as HTMLSelectElement).value).toBe("Asia/Tokyo");
  });

  it("has the storage consent checkbox linking to the privacy page", () => {
    render(<BirthForm />);
    const box = screen.getByRole("checkbox", { name: /Ta được lưu ngày giờ sinh của con \(đã mã hóa\) để lập lá số\./ });
    expect(box).toHaveProperty("name", "consent");
    const label = box.closest("div")!;
    expect(label.textContent).toContain(
      "Ta được lưu ngày giờ sinh của con (đã mã hóa) để lập lá số. Xem chính sách riêng tư.",
    );
    expect(within(label).getByRole("link", { name: "Xem chính sách riêng tư" }).getAttribute("href")).toBe(
      "/vi/chinh-sach-rieng-tu",
    );
  });

  it("shows the consent error as an alert and keeps the box checked after a failed submit", () => {
    render(
      <BirthForm
        initialState={{
          errors: { consent: "Con cần đồng ý để ta lưu ngày giờ sinh." },
          values: { date: "", time: "", gender: "", zone: "", consent: "1" },
        }}
      />,
    );
    expect(screen.getByRole("alert").textContent).toBe("Con cần đồng ý để ta lưu ngày giờ sinh.");
    expect((screen.getByRole("checkbox") as HTMLInputElement).checked).toBe(true);
  });

  it("shows the gentle unavailable message", () => {
    render(<BirthForm initialState={{ errors: {}, unavailable: true }} />);
    expect(screen.getByRole("alert").textContent).toContain("cần một lát");
  });
});

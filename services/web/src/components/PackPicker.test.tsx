import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

vi.mock("@/app/vi/mua/actions", () => ({ buyAction: vi.fn(async () => null) }));

import { PackPicker, type Pack } from "./PackPicker";

const PACKS: Pack[] = [
  { code: "CREDIT_1", title: "1 lượt", hint: "Thử một câu hỏi", price: "29.000đ" },
  { code: "CREDIT_5", title: "5 lượt", hint: "23.800đ mỗi lượt", price: "119.000đ" },
  { code: "PASS_YEAR", title: "Thẻ năm", hint: "365 ngày", price: "399.000đ" },
];

afterEach(cleanup);

describe("PackPicker", () => {
  it("preselects 5 lượt; the button and the submitted product follow the selection", () => {
    const { container } = render(<PackPicker packs={PACKS} />);
    const product = () => (container.querySelector('input[type="hidden"][name="product"]') as HTMLInputElement).value;
    expect((screen.getByRole("radio", { name: /^5 lượt/ }) as HTMLInputElement).checked).toBe(true);
    expect(screen.getByRole("button", { name: "Thanh toán 119.000đ" })).toBeTruthy();
    expect(product()).toBe("CREDIT_5");
    fireEvent.click(screen.getByRole("radio", { name: /^1 lượt/ }));
    expect(screen.getByRole("button", { name: "Thanh toán 29.000đ" })).toBeTruthy();
    expect(product()).toBe("CREDIT_1");
  });

  it("has a labelled 18+ checkbox with the terms and refund links beside it", () => {
    render(<PackPicker packs={PACKS} />);
    expect(screen.getByLabelText("Tôi đủ 18 tuổi").getAttribute("name")).toBe("adult");
    expect(screen.getByRole("link", { name: "Điều khoản" }).getAttribute("href")).toBe("/vi/dieu-khoan");
    expect(screen.getByRole("link", { name: "Chính sách hoàn tiền" }).getAttribute("href")).toBe("/vi/hoan-tien");
  });
});

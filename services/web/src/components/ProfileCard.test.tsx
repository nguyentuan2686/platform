import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { ProfileCard } from "./ProfileCard";
import type { Profile } from "@/lib/identity";

afterEach(cleanup);

const LOCK = "Mỗi tài khoản một lá số. Ngày giờ sinh đã khóa sau lượt hỏi đầu tiên.";
const base: Profile = {
  id: "p",
  label: "Lá số của tôi",
  birth: { birth: "1990-01-15T08:30", zone: "Asia/Ho_Chi_Minh", gender: "female" },
  calendar: "VN",
  locked: false,
};

describe("ProfileCard", () => {
  it("shows label, giới tính, giờ, năm can chi and lịch, never the birth date", () => {
    const { container } = render(<ProfileCard profile={base} chart={{ hour: "giờ Thìn", year: "Canh Ngọ" }} />);
    const text = container.textContent ?? "";
    expect(text).toContain("Lá số của tôi");
    expect(text).toContain("Nữ");
    expect(text).toContain("giờ Thìn");
    expect(text).toContain("Canh Ngọ");
    expect(text).toContain("Lịch Việt");
    expect(text).not.toMatch(/1990|01-15|08:30|15\/01/);
  });

  it("names the Chinese calendar", () => {
    render(<ProfileCard profile={{ ...base, calendar: "CN" }} chart={null} />);
    expect(screen.getByText("Lịch Trung Hoa")).toBeTruthy();
  });

  it("shows the lock line and hides 'Sửa thông tin' when locked", () => {
    render(<ProfileCard profile={{ ...base, locked: true }} chart={null} />);
    expect(screen.getByText(LOCK)).toBeTruthy();
    expect(screen.queryByRole("link", { name: "Sửa thông tin" })).toBeNull();
  });

  it("offers 'Sửa thông tin' to /vi and no lock line when unlocked", () => {
    render(<ProfileCard profile={base} chart={null} />);
    expect(screen.queryByText(LOCK)).toBeNull();
    expect(screen.getByRole("link", { name: "Sửa thông tin" }).getAttribute("href")).toBe("/vi");
  });
});

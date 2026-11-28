import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NumberCheckForm } from "./NumberCheckForm";

const RESULT = {
  score: 7,
  band: "kha_hop",
  bandLabel: "Khá hợp",
  menh: { canChi: "Canh Ngọ", napAm: "Lộ Bàng Thổ", element: "Thổ" },
  cungPhi: { name: "Khảm", group: "Đông tứ mệnh" },
  criteria: [
    { key: "element", label: "Ngũ hành", detail: "Thổ hòa Thổ", points: 2.5, max: 3 },
    { key: "hexagram", label: "Quẻ Kinh Dịch", detail: "Sơn Thủy Mông", points: 1.25, max: 2.5 },
    { key: "yinyang", label: "Âm dương", detail: "5 chẵn / 5 lẻ", points: 1.5, max: 1.5 },
    { key: "cungphi", label: "Cung phi", detail: "Hợp 1 quái", points: 0.75, max: 1.5 },
    { key: "nut", label: "Số nút", detail: "5 nút", points: 0, max: 0.5 },
    { key: "pairs", label: "Cặp số dân gian", detail: "78", points: 0.75, max: 1 },
  ],
  reading: ["Đoạn một của ta.", "Đoạn hai của ta."],
  quota: { limit: 2, used: 1, remaining: 1 },
};
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

let fetchMock: ReturnType<typeof vi.fn>;
let post: () => Response;
beforeEach(() => {
  post = () => json(200, RESULT);
  fetchMock = vi.fn(async (url: string) => (url === "/api/xem-so/quota" ? json(200, { limit: 2, used: 0, remaining: 2 }) : post()));
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function fill(number: string) {
  fireEvent.change(screen.getByLabelText(/Số điện thoại|Số tài khoản|Biển số xe/), { target: { value: number } });
  fireEvent.change(screen.getByLabelText(/Năm sinh/), { target: { value: "1990" } });
  fireEvent.submit(screen.getByRole("form", { name: "Xem số" }));
}

describe("NumberCheckForm", () => {
  it("shows the bank warning only for a bank account and the vehicle select only for a plate", () => {
    const { unmount } = render(<NumberCheckForm kind="bank" numberLabel="Số tài khoản" placeholder="" />);
    expect(document.body.textContent).toContain("Không bao giờ nhập mật khẩu, mã OTP hay số thẻ.");
    expect(screen.queryByLabelText("Loại xe")).toBeNull();
    unmount();
    render(<NumberCheckForm kind="plate" numberLabel="Biển số xe" placeholder="" />);
    expect(screen.getByLabelText("Loại xe")).toBeTruthy();
    expect(document.body.textContent).not.toContain("mã OTP");
    cleanup();
    render(<NumberCheckForm kind="sim" numberLabel="Số điện thoại" placeholder="" />);
    expect(screen.queryByLabelText("Loại xe")).toBeNull();
    expect(document.body.textContent).not.toContain("mã OTP");
  });

  it("posts JSON to /api/xem-so (never a GET, the URL unchanged) and shows the result", async () => {
    const before = window.location.href;
    render(<NumberCheckForm kind="sim" numberLabel="Số điện thoại" placeholder="" />);
    await waitFor(() => expect(document.body.textContent).toContain("còn 2 lượt"));
    fill("0912345678");
    await waitFor(() => expect(screen.getByRole("heading", { level: 2 }).textContent).toContain("7 / 10"));
    const call = fetchMock.mock.calls.find((c) => c[0] === "/api/xem-so")!;
    expect(call[1].method).toBe("POST");
    expect(JSON.parse(call[1].body)).toEqual({ kind: "sim", number: "0912345678", birthYear: 1990, beforeTet: false, gender: "male" });
    expect(window.location.href).toBe(before);
    expect(document.body.textContent).toContain("Khá hợp");
    expect(screen.getAllByRole("row")).toHaveLength(6);
    expect(document.body.textContent).toContain("Đoạn một của ta.");
    expect(document.body.textContent).toContain("Đoạn hai của ta.");
    expect(document.body.textContent).toContain("còn 1 lượt");
  });

  it("sends the vehicle for a plate", async () => {
    render(<NumberCheckForm kind="plate" numberLabel="Biển số xe" placeholder="" />);
    fireEvent.change(screen.getByLabelText("Loại xe"), { target: { value: "motorbike" } });
    fill("29B1-123.45");
    await waitFor(() => expect(fetchMock.mock.calls.some((c) => c[0] === "/api/xem-so")).toBe(true));
    const call = fetchMock.mock.calls.find((c) => c[0] === "/api/xem-so")!;
    expect(JSON.parse(call[1].body)).toMatchObject({ kind: "plate", vehicle: "motorbike" });
  });

  it("on 409 shows the daily-limit message and a link to /vi", async () => {
    post = () => json(409, { reason: "number_quota", quota: { limit: 2, used: 2, remaining: 0 } });
    render(<NumberCheckForm kind="sim" numberLabel="Số điện thoại" placeholder="" />);
    fill("0911111111");
    await waitFor(() => expect(document.body.textContent).toContain("Hôm nay con xem đủ 2 số rồi, mai quay lại với ta nhé."));
    expect(screen.getByRole("link", { name: /Xem lá số/ }).getAttribute("href")).toBe("/vi");
  });

  it("explains an invalid number and an upstream failure", async () => {
    post = () => json(400, { reason: "invalid", field: "number" });
    render(<NumberCheckForm kind="sim" numberLabel="Số điện thoại" placeholder="" />);
    fill("0912");
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("chưa đúng định dạng"));
    post = () => json(503, { reason: "unavailable" });
    fill("0912345678");
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("thử lại sau"));
  });
});

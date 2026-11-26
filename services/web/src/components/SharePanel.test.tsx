import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const m = vi.hoisted(() => ({ create: vi.fn(), del: vi.fn() }));
vi.mock("@/app/vi/la-so/shareActions", () => ({
  createShareAction: (...a: unknown[]) => m.create(...a),
  deleteShareAction: (...a: unknown[]) => m.del(...a),
}));

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { SharePanel } from "@/components/SharePanel";

const ID = "abcdefghijklmnopqrstuv";
const URL_ = `https://tuvi.example/vi/chia-se/${ID}`;
const active = <SharePanel share={{ share_id: ID }} summary="Mệnh con vững." url={URL_} />;

function setNav(props: Record<string, unknown>) {
  for (const [k, v] of Object.entries(props)) Object.defineProperty(navigator, k, { value: v, configurable: true });
}

beforeEach(() => {
  m.create.mockResolvedValue(null);
  m.del.mockResolvedValue(null);
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  setNav({ share: undefined, clipboard: undefined });
});

describe("SharePanel without a link", () => {
  it("offers to create one and explains what viewers see", () => {
    render(<SharePanel share={null} summary="" url="" />);
    expect(screen.getByRole("button", { name: "Tạo link công khai" })).toBeTruthy();
    expect(screen.getByText("Người xem thấy lá số và luận giải cơ bản. Ngày dương lịch được ẩn.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Tắt link" })).toBeNull();
  });

  it("creates through the action", async () => {
    render(<SharePanel share={null} summary="" url="" />);
    fireEvent.click(screen.getByRole("button", { name: "Tạo link công khai" }));
    await waitFor(() => expect(m.create).toHaveBeenCalled());
  });
});

describe("SharePanel with a link", () => {
  it("shows the link read-only and the download, Facebook and revoke controls", () => {
    render(active);
    const field = screen.getByLabelText("Link công khai") as HTMLInputElement;
    expect(field.value).toBe(URL_);
    expect(field.readOnly).toBe(true);
    const dl = screen.getByRole("link", { name: "Tải ảnh" });
    expect(dl.getAttribute("href")).toBe(`/vi/chia-se/${ID}/the.png`);
    expect(dl.getAttribute("download")).toBe("la-so-tu-vi.png");
    expect(screen.getByRole("button", { name: "Tắt link" })).toBeTruthy();
  });

  it("links to Facebook with an encoded url, in a new tab without opener", () => {
    render(active);
    const fb = screen.getByRole("link", { name: "Facebook" });
    expect(fb.getAttribute("href")).toBe(`https://www.facebook.com/sharer/sharer.php?u=${encodeURIComponent(URL_)}`);
    expect(fb.getAttribute("target")).toBe("_blank");
    expect(fb.getAttribute("rel")).toBe("noopener noreferrer");
  });

  it("hides the native share button when the device has no Web Share API", () => {
    setNav({ share: undefined });
    render(active);
    expect(screen.queryByRole("button", { name: "Gửi qua Zalo, Messenger…" })).toBeNull();
  });

  it("shares title, summary and url through navigator.share when it exists", async () => {
    const share = vi.fn().mockResolvedValue(undefined);
    setNav({ share });
    render(active);
    fireEvent.click(await screen.findByRole("button", { name: "Gửi qua Zalo, Messenger…" }));
    await waitFor(() => expect(share).toHaveBeenCalledTimes(1));
    const arg = share.mock.calls[0][0];
    expect(arg.url).toBe(URL_);
    expect(arg.text).toBe("Mệnh con vững.");
    expect(typeof arg.title).toBe("string");
  });

  it("copies the link and announces it", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    setNav({ clipboard: { writeText } });
    render(active);
    fireEvent.click(screen.getByRole("button", { name: "Sao chép link" }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(URL_));
    const live = await screen.findByText("Đã chép");
    expect(live.closest("[aria-live]")?.getAttribute("aria-live")).toBe("polite");
  });

  it("says so when the clipboard is refused", async () => {
    setNav({ clipboard: { writeText: vi.fn().mockRejectedValue(new Error("denied")) } });
    render(active);
    fireEvent.click(screen.getByRole("button", { name: "Sao chép link" }));
    expect(await screen.findByText(/chép tay/)).toBeTruthy();
  });

  it("revokes through the action", async () => {
    render(active);
    fireEvent.click(screen.getByRole("button", { name: "Tắt link" }));
    await waitFor(() => expect(m.del).toHaveBeenCalled());
  });

  it("shows an action error as an alert", async () => {
    m.del.mockResolvedValue({ error: "Lão Thiên Cơ cần một lát. Con thử lại sau nhé." });
    render(active);
    fireEvent.click(screen.getByRole("button", { name: "Tắt link" }));
    expect((await screen.findByRole("alert")).textContent).toContain("cần một lát");
  });
});

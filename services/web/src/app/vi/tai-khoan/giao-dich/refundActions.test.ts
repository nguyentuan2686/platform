import { beforeEach, describe, expect, it, vi } from "vitest";

const m = vi.hoisted(() => ({
  getViewer: vi.fn(),
  requestRefund: vi.fn(),
  revalidatePath: vi.fn(),
  redirect: vi.fn((url: string) => {
    throw new Error(`REDIRECT:${url}`);
  }),
}));
vi.mock("next/navigation", () => ({ redirect: (u: string) => m.redirect(u) }));
vi.mock("next/cache", () => ({ revalidatePath: (p: string) => m.revalidatePath(p) }));
vi.mock("@/lib/session", () => ({ getViewer: () => m.getViewer() }));
vi.mock("@/lib/payment", () => ({ requestRefund: (o: string, a: string) => m.requestRefund(o, a) }));

import { refundAction } from "@/app/vi/tai-khoan/giao-dich/actions";
import { UpstreamError, UpstreamProblem, UpstreamValidation } from "@/lib/http";

const ACC = "11111111-1111-1111-1111-111111111111";
const ORD = "22222222-2222-2222-2222-222222222222";
const ACCOUNT = { kind: "account", accountId: ACC, emailMasked: "", token: "t", isAdmin: false, entitlements: { credits: 1, pass: null, features: [] } };
const form = (order_id: string) => {
  const f = new FormData();
  f.set("order_id", order_id);
  return f;
};

beforeEach(() => {
  for (const f of Object.values(m)) f.mockReset();
  m.redirect.mockImplementation((url: string) => {
    throw new Error(`REDIRECT:${url}`);
  });
  m.getViewer.mockResolvedValue(ACCOUNT);
  m.requestRefund.mockResolvedValue({ id: "r", status: "OPEN" });
});

describe("refundAction", () => {
  it("requires an account", async () => {
    m.getViewer.mockResolvedValue({ kind: "anon" });
    await expect(refundAction(null, form(ORD))).rejects.toThrow("REDIRECT:/vi/dang-nhap?next=/vi/tai-khoan/giao-dich");
    expect(m.requestRefund).not.toHaveBeenCalled();
  });

  it("acknowledges a refund request of the viewer's order", async () => {
    expect(await refundAction(null, form(ORD))).toEqual({
      ok: "Ta đã ghi nhận. Tiền sẽ được chuyển lại trong vài ngày làm việc.",
    });
    expect(m.requestRefund).toHaveBeenCalledWith(ORD, ACC);
    expect(m.revalidatePath).toHaveBeenCalledWith("/vi/tai-khoan/giao-dich");
  });

  it.each([
    ["credits_used", "Lượt của gói này đã được dùng, không hoàn tiền được."],
    ["not_refundable", "Đơn này không hoàn tiền tự động được."],
  ])("explains a 409 %s", async (reason, text) => {
    m.requestRefund.mockRejectedValue(new UpstreamProblem(409, reason));
    expect(await refundAction(null, form(ORD))).toEqual({ error: text });
  });

  it("explains an unknown order and an unavailable service", async () => {
    m.requestRefund.mockRejectedValue(new UpstreamProblem(404));
    expect((await refundAction(null, form(ORD)))?.error).toBe("Không thấy đơn này.");
    m.requestRefund.mockRejectedValue(new UpstreamError());
    expect((await refundAction(null, form(ORD)))?.error).toBe("Ta chưa ghi nhận được, con thử lại sau.");
  });

  it("rejects a malformed order id without calling payment-svc", async () => {
    expect((await refundAction(null, form("../x")))?.error).toBe("Không thấy đơn này.");
    expect(m.requestRefund).not.toHaveBeenCalled();
  });
});

describe("refundAction logging", () => {
  const logged = (w: { mock: { calls: unknown[][] } }) => w.mock.calls.map((c) => JSON.parse(String(c[0])));

  it("does not log the refusals the page explains (known 409s, 404)", async () => {
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    m.requestRefund.mockRejectedValue(new UpstreamProblem(409, "credits_used", "payment-svc"));
    await refundAction(null, form(ORD));
    m.requestRefund.mockRejectedValue(new UpstreamProblem(404, undefined, "payment-svc"));
    await refundAction(null, form(ORD));
    expect(write).not.toHaveBeenCalled();
    write.mockRestore();
  });

  it.each([
    [new UpstreamProblem(403, undefined, "payment-svc"), "403"],
    [new UpstreamProblem(409, "something_new", "payment-svc"), "409"],
    [new UpstreamValidation({ account_id: "invalid" }, "payment-svc"), "400"],
  ])("logs an unexpected refusal (%s) and asks to retry", async (err, status) => {
    const write = vi.spyOn(process.stdout, "write").mockReturnValue(true);
    m.requestRefund.mockRejectedValue(err);
    expect((await refundAction(null, form(ORD)))?.error).toBe("Ta chưa ghi nhận được, con thử lại sau.");
    const lines = logged(write);
    write.mockRestore();
    expect(lines).toEqual([expect.objectContaining({ level: "error", upstream: "payment-svc", status, part: "refund" })]);
  });
});

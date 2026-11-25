import { describe, expect, it } from "vitest";
import { formatDateTimeVn, formatDateVn, formatVnd } from "@/lib/format";

describe("formatVnd", () => {
  it("uses dot thousands and no decimals", () => {
    expect(formatVnd("29000.00")).toBe("29.000đ");
    expect(formatVnd("119000.00")).toBe("119.000đ");
    expect(formatVnd("399000.00")).toBe("399.000đ");
    expect(formatVnd("1234567.00")).toBe("1.234.567đ");
    expect(formatVnd("500.00")).toBe("500đ");
    expect(formatVnd("0.00")).toBe("0đ");
  });
});

describe("formatDateVn / formatDateTimeVn", () => {
  it("shows the date in Asia/Ho_Chi_Minh", () => {
    expect(formatDateVn("2026-11-24T17:30:00Z")).toBe("25/11/2026");
    expect(formatDateVn("2026-01-05T03:00:00Z")).toBe("05/01/2026");
  });

  it("shows HH:mm dd/mm/yyyy in Asia/Ho_Chi_Minh", () => {
    expect(formatDateTimeVn("2026-11-24T17:30:00Z")).toBe("00:30 25/11/2026");
    expect(formatDateTimeVn("2026-11-25T08:05:00Z")).toBe("15:05 25/11/2026");
  });
});

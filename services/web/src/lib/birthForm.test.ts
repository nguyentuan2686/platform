import { describe, expect, it } from "vitest";
import { parseBirthForm } from "@/lib/birthForm";

function form(o: Record<string, string>): FormData {
  const fd = new FormData();
  for (const [k, v] of Object.entries(o)) fd.set(k, v);
  return fd;
}
const ok = { date: "1994-03-12", time: "08:30", gender: "male", zone: "Asia/Ho_Chi_Minh", consent: "1" };

describe("parseBirthForm", () => {
  it("accepts a valid submission and builds the upstream birth string", () => {
    const r = parseBirthForm(form(ok));
    expect(r).toEqual({
      ok: true,
      birth: { birth: "1994-03-12T08:30", zone: "Asia/Ho_Chi_Minh", gender: "male", calendar: "VN" },
    });
  });

  it("accepts dd/mm/yyyy and 23:40", () => {
    const r = parseBirthForm(form({ ...ok, date: "12/03/1994", time: "23:40" }));
    expect(r.ok && r.birth.birth).toBe("1994-03-12T23:40");
  });

  it("defaults the zone to Việt Nam", () => {
    const r = parseBirthForm(form({ date: ok.date, time: ok.time, gender: "female", consent: "on" }));
    expect(r.ok && r.birth.zone).toBe("Asia/Ho_Chi_Minh");
  });

  it("rejects an impossible date", () => {
    const r = parseBirthForm(form({ ...ok, date: "30/02/1994" }));
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.errors.date).toBeTruthy();
  });

  it("rejects years outside 1900-2100", () => {
    for (const date of ["1899-12-31", "2101-01-01"]) {
      const r = parseBirthForm(form({ ...ok, date }));
      expect(r.ok).toBe(false);
      if (!r.ok) expect(r.errors.date).toBeTruthy();
    }
  });

  it("rejects a bad time, missing gender and unknown zone", () => {
    const r = parseBirthForm(form({ date: ok.date, time: "25:00", zone: "Mars/Base" }));
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.errors.time).toBeTruthy();
      expect(r.errors.gender).toBeTruthy();
      expect(r.errors.zone).toBeTruthy();
    }
  });

  it("requires the storage consent", () => {
    const r = parseBirthForm(form({ date: ok.date, time: ok.time, gender: ok.gender, zone: ok.zone }));
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.errors.consent).toBe("Con cần đồng ý để ta lưu ngày giờ sinh.");
  });
});

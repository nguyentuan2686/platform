import { beforeEach, describe, expect, it, vi } from "vitest";

const h = vi.hoisted(() => ({ value: new Headers() }));
vi.mock("next/headers", () => ({ headers: async () => h.value }));

import { clientIp } from "@/lib/clientIp";

describe("clientIp", () => {
  beforeEach(() => {
    h.value = new Headers();
  });

  it("is the Cf-Connecting-IP header, trimmed", async () => {
    h.value.set("cf-connecting-ip", " 203.0.113.7 ");
    expect(await clientIp()).toBe("203.0.113.7");
    h.value.set("cf-connecting-ip", "2001:db8:1:2::1");
    expect(await clientIp()).toBe("2001:db8:1:2::1");
  });

  it("is undefined when absent or not shaped like an IP literal", async () => {
    expect(await clientIp()).toBeUndefined();
    for (const bad of ["", "localhost", "203.0.113.7, 198.51.100.1", "a".repeat(46), "1.2.3.4\nx"]) {
      h.value = new Headers();
      try {
        h.value.set("cf-connecting-ip", bad);
      } catch {
        continue; // catch-ok: Headers refuses a newline itself, which is just as safe
      }
      expect(await clientIp()).toBeUndefined();
    }
  });
});

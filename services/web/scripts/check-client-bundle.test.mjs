// @vitest-environment node
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { FORBIDDEN, findLeaks } from "./check-client-bundle.mjs";

let dir;
afterEach(() => dir && rmSync(dir, { recursive: true, force: true }));

function tree(files) {
  dir = mkdtempSync(join(tmpdir(), "bundle-"));
  for (const [p, content] of Object.entries(files)) {
    mkdirSync(join(dir, p, ".."), { recursive: true });
    writeFileSync(join(dir, p), content);
  }
  return dir;
}

describe("check-client-bundle", () => {
  it("lists the forbidden markers", () => {
    for (const s of ["X-Internal-Token", "X-Admin-Token", "local-test-", ":8082", ":8083", ":8084", ":8085", "IDENTITY_SVC_URL", "PAYMENT_SVC_URL"]) {
      expect(FORBIDDEN).toContain(s);
    }
  });

  it("passes a clean tree", () => {
    expect(findLeaks(tree({ "chunks/a.js": "console.log(1)", "css/b.css": "body{}" }))).toEqual([]);
  });

  it("reports each file with a forbidden marker, recursively", () => {
    const d = tree({
      "chunks/app/x.js": 'fetch("http://identity:8084/me")',
      "chunks/y.js": 'h["X-Internal-Token"]=t',
      "z.js": "ok",
    });
    const leaks = findLeaks(d).map((l) => [l.file.replaceAll("\\", "/").replace(d.replaceAll("\\", "/") + "/", ""), l.marker]);
    expect(leaks).toEqual(
      expect.arrayContaining([
        ["chunks/app/x.js", ":8084"],
        ["chunks/y.js", "X-Internal-Token"],
      ]),
    );
    expect(leaks).toHaveLength(2);
  });

  it("also flags extra markers such as the configured token value", () => {
    const d = tree({ "a.js": "var t='s3cr3t-value'" });
    expect(findLeaks(d, ["s3cr3t-value"])).toHaveLength(1);
  });
});

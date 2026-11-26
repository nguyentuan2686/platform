import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import ts from "typescript";
import { describe, expect, it } from "vitest";

/**
 * Hard check for the CLAUDE.md rule "never an empty or log-less catch" (lesson
 * docs/lessons/2026-11-26-swallowed-upstream-errors.md). Every `catch` clause in src/** (tests
 * excluded) must, in its own body (not inside a nested function):
 * - rethrow (`throw`), or
 * - hand the error to a shared handler that logs or rethrows: log, logRefusalOrThrow,
 *   degradeOrThrow, upstreamFailure, upstreamMessage, or
 * - say why swallowing is right with a `catch-ok: <reason>` comment (client code with no server
 *   log to write to, parsing untrusted input, a browser API that may be missing).
 */
const HANDLERS = new Set(["log", "logRefusalOrThrow", "degradeOrThrow", "upstreamFailure", "upstreamMessage"]);
const ALLOW = /catch-ok:\s*\S/;
const SRC = join(__dirname);

function sources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((d) => {
    const p = join(dir, d.name);
    if (d.isDirectory()) return sources(p);
    return /\.tsx?$/.test(d.name) && !/\.test\.tsx?$/.test(d.name) && !d.name.endsWith(".d.ts") ? [p] : [];
  });
}

function handles(block: ts.Block): boolean {
  let ok = false;
  const visit = (n: ts.Node): void => {
    if (ok || ts.isFunctionLike(n)) return;
    if (ts.isThrowStatement(n)) ok = true;
    else if (ts.isCallExpression(n) && ts.isIdentifier(n.expression) && HANDLERS.has(n.expression.text)) ok = true;
    else ts.forEachChild(n, visit);
  };
  ts.forEachChild(block, visit);
  return ok;
}

/** `file:line` of every catch clause that swallows the error silently. */
export function silentCatches(file: string, text: string): string[] {
  const sf = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, file.endsWith("x") ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
  const found: string[] = [];
  const visit = (n: ts.Node): void => {
    if (ts.isCatchClause(n) && !handles(n.block) && !ALLOW.test(n.block.getFullText(sf))) {
      found.push(`${file}:${sf.getLineAndCharacterOfPosition(n.getStart(sf)).line + 1}`);
    }
    ts.forEachChild(n, visit);
  };
  visit(sf);
  return found;
}

describe("catch policy", () => {
  it("flags an empty catch, a log-less catch and a comment-only catch; accepts the handled forms", () => {
    const bad = [
      "try { f() } catch {}",
      "try { f() } catch (e) {}",
      "try { f() } catch { /* ignore */ }",
      "try { f() } catch (e) { x = 1 }",
      "try { f() } catch (e) { const g = () => { throw e } }", // a throw in a nested function does not count
    ];
    for (const src of bad) expect(silentCatches("x.ts", src), src).toHaveLength(1);
    const good = [
      "try { f() } catch (e) { throw e }",
      "try { f() } catch (e) { if (e instanceof X) return 1; throw e }",
      'try { f() } catch (e) { logRefusalOrThrow(e, "part"); return null }',
      'try { f() } catch (e) { degradeOrThrow(e, "part") }',
      'try { f() } catch { log("warn", "m", {}) }',
      "try { f() } catch { /* catch-ok: unparsable body is treated as empty */ }",
    ];
    for (const src of good) expect(silentCatches("x.ts", src), src).toEqual([]);
  });

  it("finds no silent catch in src/**", () => {
    const offenders = sources(SRC).flatMap((f) => silentCatches(relative(SRC, f), readFileSync(f, "utf8")));
    expect(offenders).toEqual([]);
  });
});

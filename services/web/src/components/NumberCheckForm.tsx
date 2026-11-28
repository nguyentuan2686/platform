"use client";

import Link from "next/link";
import { useEffect, useState, type FormEvent } from "react";
import { BANK_WARNING, DAILY_LIMIT_NOTE, IP_QUOTA_MESSAGE, QUOTA_MESSAGE } from "@/content/xemSo";

type Kind = "sim" | "bank" | "plate";
type Quota = { limit: number; used: number; remaining: number };
type Criterion = { key: string; label: string; detail: string; points: number; max: number };
type Result = {
  score: number;
  bandLabel: string;
  menh: { canChi: string; napAm: string; element: string };
  cungPhi: { name: string; group: string };
  criteria: Criterion[];
  reading: string[];
  quota: Quota;
};
type State =
  | { s: "idle" }
  | { s: "busy" }
  | { s: "done"; r: Result }
  | { s: "quota"; ip: boolean }
  | { s: "invalid"; field: string }
  | { s: "error" };

const MIN_YEAR = 1930;
const fmt = (n: number) => n.toLocaleString("vi-VN", { maximumFractionDigits: 2 });
const INVALID_TEXT: Record<string, string> = {
  number: "Dãy số chưa đúng định dạng, con xem lại giúp ta nhé.",
  birthYear: "Năm sinh chưa đúng, con nhập năm dương lịch từ 1930 nhé.",
  vehicle: "Con chọn ô tô hay xe máy giúp ta nhé.",
};

/**
 * The number-check form. Posts JSON to /api/xem-so with fetch (never a GET form: the number must not
 * reach a URL, history or analytics) and shows the score on the page; nothing is shareable.
 */
export function NumberCheckForm({ kind, numberLabel, placeholder }: { kind: Kind; numberLabel: string; placeholder: string }) {
  const [state, setState] = useState<State>({ s: "idle" });
  const [quota, setQuota] = useState<Quota | null>(null);
  const maxYear = new Date().getFullYear();

  useEffect(() => {
    let live = true;
    fetch("/api/xem-so/quota", { cache: "no-store" })
      .then(async (res) => {
        if (live && res.status === 200) setQuota((await res.json()) as Quota);
      })
      .catch(() => {
        // catch-ok: the quota line is optional; the form still works without it
      });
    return () => {
      live = false;
    };
  }, []);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const body = {
      kind,
      ...(kind === "plate" ? { vehicle: String(f.get("vehicle") ?? "") } : {}),
      number: String(f.get("number") ?? ""),
      birthYear: Number(f.get("birthYear")),
      beforeTet: f.get("beforeTet") === "on",
      gender: String(f.get("gender") ?? ""),
    };
    setState({ s: "busy" });
    try {
      const res = await fetch("/api/xem-so", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
        cache: "no-store",
      });
      if (res.status === 200) {
        const r = (await res.json()) as Result;
        setQuota(r.quota);
        setState({ s: "done", r });
      } else if (res.status === 409) {
        const p = (await res.json().catch(() => ({}))) as { reason?: string };
        const ip = p.reason === "number_quota_ip";
        if (!ip) setQuota((q) => (q ? { ...q, used: q.limit, remaining: 0 } : q));
        setState({ s: "quota", ip });
      } else if (res.status === 400) {
        const p = (await res.json().catch(() => ({}))) as { field?: string };
        setState({ s: "invalid", field: p.field ?? "number" });
      } else {
        setState({ s: "error" });
      }
    } catch {
      // catch-ok: a network failure in the browser is shown as the retry message
      setState({ s: "error" });
    }
  }

  return (
    <section className="number-check">
      <form onSubmit={onSubmit} className="birth-form" aria-label="Xem số">
        <label>
          {numberLabel}
          <input name="number" required inputMode={kind === "plate" ? "text" : "numeric"} autoComplete="off" placeholder={placeholder} maxLength={32} />
        </label>
        {kind === "bank" && <p className="warning">{BANK_WARNING}</p>}
        {kind === "plate" && (
          <label>
            Loại xe
            <select name="vehicle" defaultValue="car">
              <option value="car">Ô tô</option>
              <option value="motorbike">Xe máy</option>
            </select>
          </label>
        )}
        <label>
          Năm sinh (dương lịch)
          <input name="birthYear" type="number" required min={MIN_YEAR} max={maxYear} inputMode="numeric" autoComplete="off" />
        </label>
        <label className="check">
          <input name="beforeTet" type="checkbox" /> Sinh trước Tết Nguyên đán năm đó
        </label>
        <fieldset>
          <legend>Giới tính</legend>
          <label>
            <input type="radio" name="gender" value="male" required defaultChecked /> Nam
          </label>
          <label>
            <input type="radio" name="gender" value="female" /> Nữ
          </label>
        </fieldset>
        <button type="submit" className="btn" disabled={state.s === "busy"}>
          {state.s === "busy" ? "Ta đang xem…" : "Xem số"}
        </button>
        <p className="updated">{quota ? `Ta không lưu dãy số của con. Hôm nay con còn ${quota.remaining} lượt xem.` : DAILY_LIMIT_NOTE}</p>
      </form>

      <div aria-live="polite">
        {state.s === "quota" && (
          <div className="notice" role="status">
            <p>{state.ip ? IP_QUOTA_MESSAGE : QUOTA_MESSAGE}</p>
            <Link href="/vi" className="btn btn-ghost">
              Xem lá số tử vi của con
            </Link>
          </div>
        )}
        {state.s === "invalid" && <p role="alert">{INVALID_TEXT[state.field] ?? INVALID_TEXT.number}</p>}
        {state.s === "error" && <p role="alert">Ta đang bận một chút, con thử lại sau nhé.</p>}
        {state.s === "done" && <NumberResult r={state.r} />}
      </div>
    </section>
  );
}

function NumberResult({ r }: { r: Result }) {
  return (
    <article className="number-result">
      <h2>
        Điểm hợp mệnh: {fmt(r.score)} / 10 ({r.bandLabel})
      </h2>
      <p>
        Mệnh con: {r.menh.napAm} ({r.menh.canChi}) · Cung phi {r.cungPhi.name} ({r.cungPhi.group})
      </p>
      <table>
        <tbody>
          {r.criteria.map((c) => (
            <tr key={c.key}>
              <th scope="row">{c.label}</th>
              <td>{c.detail}</td>
              <td>
                {fmt(c.points)}/{fmt(c.max)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <h3>Lời thầy</h3>
      {r.reading.map((p) => (
        <p key={p}>{p}</p>
      ))}
      <p>
        <Link href="/vi" className="btn btn-ghost">
          Xem lá số tử vi của con →
        </Link>
      </p>
    </article>
  );
}

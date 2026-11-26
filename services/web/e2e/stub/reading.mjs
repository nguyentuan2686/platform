// reading-tuvi stub: basic and today readings answer the captured fixtures; the Q&A API (asks and
// follow-ups as SSE, history list/get/delete, free quota, guest merge) behaves like the service, in
// memory (server.mjs records every call, see GET /__calls). Questions are scripted by their text:
// "bệnh" is refused (never charged), "lỗi" is queued (PAID) or busy (FREE), anything else streams
// three sentences and is done.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import * as identity from "./identity.mjs";

export const NAME = "reading-tuvi";
const fx = (n) => readFileSync(fileURLToPath(new URL(`../fixtures/${n}.json`, import.meta.url)), "utf8");
const routes = { "/tuvi/readings/basic": fx("basic"), "/tuvi/readings/today": fx("today") };

const TOPICS = ["TinhDuyen", "SuNghiep", "TaiLoc", "SucKhoe", "GiaDao", "VanNam"];
const FREE_LIMIT = 3;
const SENTENCE_DELAY_MS = 150;
const DISCLAIMER = "Lời luận chỉ để tham khảo, con tự cân nhắc quyết định của mình.";
const ANSWER = [
  "Cung Quan Lộc của con có Tử Vi tọa thủ, là thế đứng vững vàng và hợp với việc quản lý người khác. ",
  "Năm nay sao Thiên Cơ chiếu vào, con nên cẩn trọng giấy tờ và đừng vội đổi việc trước mùa thu. ",
  "Cứ kiên nhẫn tích lũy, thời vận sẽ mở ra khi con đã chuẩn bị đủ chín chắn. ",
];
const FOLLOW_UP = [
  "Về chuyện con hỏi thêm, ta thấy mọi việc đều cần thời gian để chín, đừng nóng vội mà hỏng chuyện. ",
  "Giữ lòng bình tĩnh và nghe lời người đi trước, con sẽ thấy đường đi sáng dần. ",
];
const QUEUED_TEXT = "Thầy đang bận, bài luận sẽ có trong Lịch sử khi xong.";
const BUSY_TEXT = "Thầy đang bận, con thử lại sau một lát nhé.";
const REFUSED_TEXT = "Chuyện bệnh tật ta không luận, con hãy hỏi thầy thuốc.";

/** reading_id -> { id, subject, kind, topic, status, created_at, follow_ups_left, notice, retry_until, messages } */
const readings = new Map();
/** subject id -> free chats used today */
const freeUsed = new Map();

export function reset() {
  readings.clear();
  freeUsed.clear();
}

const problem = (status, reason) => ({ type: "urn:problem:stub", title: "stub", status, trace_id: "", ...(reason ? { reason } : {}) });
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const bearer = (req) => {
  const h = req.headers.authorization ?? "";
  return h.startsWith("Bearer ") ? h.slice(7) : undefined;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const ev = (event, data) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;

function summary(r) {
  return { reading_id: r.id, kind: r.kind, topic: r.topic, status: r.status, created_at: r.created_at, follow_ups_left: r.follow_ups_left };
}

/** Writes SSE frames, pausing between sentences; stops quietly when the client went away. */
async function stream(res, frames, delay) {
  res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" });
  for (const f of frames) {
    if (res.destroyed || res.writableEnded) return;
    res.write(f);
    if (delay && f.startsWith("event: delta")) await sleep(SENTENCE_DELAY_MS);
  }
  res.end();
}

function outcomeFrames(r) {
  const meta = ev("meta", { reading_id: r.id, kind: r.kind, topic: r.topic, follow_ups_left: r.follow_ups_left });
  const first = r.messages[0];
  if (r.status === "ANSWERED") {
    return [meta, ...ANSWER.map((t) => ev("delta", { text: t })), ev("done", { reading_id: r.id, seq: first.seq, disclaimer: DISCLAIMER })];
  }
  if (r.status === "REFUSED") return [meta, ev("refused", { reading_id: r.id, reason: "medical", text: REFUSED_TEXT, disclaimer: DISCLAIMER })];
  return [meta, ev("queued", { reading_id: r.id, retry_until: r.retry_until, text: QUEUED_TEXT })];
}

async function ask(req, res, body, send, who, token) {
  const q = body?.question;
  if (
    !UUID.test(body?.reading_id ?? "") ||
    (body.kind !== "PAID" && body.kind !== "FREE") ||
    !TOPICS.includes(body.topic) ||
    body.lang !== "vi" ||
    typeof q !== "string" ||
    q.length < 5 ||
    q.length > 500
  ) {
    send(res, 400, problem(400), "application/problem+json");
    return;
  }
  const existing = readings.get(body.reading_id);
  if (existing) {
    if (existing.subject !== who.id) send(res, 409, problem(409, "reading_conflict"), "application/problem+json");
    else await stream(res, outcomeFrames(existing), false);
    return;
  }
  if (body.kind === "PAID" && who.kind !== "account") {
    send(res, 403, problem(403, "account_required"), "application/problem+json");
    return;
  }
  if (!identity.hasProfile(who.id)) {
    send(res, 409, problem(409, "no_profile"), "application/problem+json");
    return;
  }
  const refused = q.includes("bệnh");
  if (body.kind === "FREE" && !refused && (freeUsed.get(who.id) ?? 0) >= FREE_LIMIT) {
    send(res, 409, problem(409, "free_quota"), "application/problem+json");
    return;
  }
  if (body.kind === "PAID" && !refused && !identity.consume(token)) {
    send(res, 409, problem(409, "no_credits"), "application/problem+json");
    return;
  }
  const queued = q.includes("lỗi");
  const r = {
    id: body.reading_id,
    subject: who.id,
    kind: body.kind,
    topic: body.topic,
    created_at: new Date().toISOString(),
    follow_ups_left: body.kind === "PAID" ? 2 : 0,
    notice: null,
    retry_until: null,
    status: refused ? "REFUSED" : queued && body.kind === "PAID" ? "RETRYING" : "ANSWERED",
    messages: [{ seq: 0, question: q, answer: ANSWER.join(""), created_at: new Date().toISOString() }],
  };
  if (refused) {
    r.follow_ups_left = 0;
    r.messages[0].answer = REFUSED_TEXT;
  }
  if (r.status === "RETRYING") {
    r.follow_ups_left = 0;
    r.messages[0].answer = null;
    r.notice = QUEUED_TEXT;
    r.retry_until = new Date(Date.now() + 3600_000).toISOString();
  }
  if (body.kind === "FREE" && queued) {
    // A free chat that no provider can answer: busy, nothing stored, not counted.
    await stream(res, [ev("meta", { reading_id: r.id, kind: r.kind, topic: r.topic, follow_ups_left: 0 }), ev("busy", { text: BUSY_TEXT })], false);
    return;
  }
  readings.set(r.id, r);
  if (body.kind === "FREE" && !refused) freeUsed.set(who.id, (freeUsed.get(who.id) ?? 0) + 1);
  await stream(res, outcomeFrames(r), true);
}

async function followUp(res, id, body, send, who) {
  const r = readings.get(id);
  if (!r || r.subject !== who.id) {
    send(res, 404, problem(404), "application/problem+json");
    return;
  }
  if (!UUID.test(body?.message_id ?? "") || typeof body?.question !== "string" || body.question.length < 5 || body.question.length > 500) {
    send(res, 400, problem(400), "application/problem+json");
    return;
  }
  if (r.kind !== "PAID" || r.status !== "ANSWERED") {
    send(res, 409, problem(409, "reading_not_answered"), "application/problem+json");
    return;
  }
  if (r.follow_ups_left < 1) {
    send(res, 409, problem(409, "follow_ups_exhausted"), "application/problem+json");
    return;
  }
  const seq = r.messages.length;
  if (body.question.includes("bệnh") || body.question.includes("lỗi")) {
    const text = body.question.includes("bệnh") ? REFUSED_TEXT : BUSY_TEXT;
    const meta = ev("meta", { reading_id: r.id, kind: r.kind, topic: r.topic, follow_ups_left: r.follow_ups_left });
    await stream(res, [meta, ev(body.question.includes("bệnh") ? "refused" : "busy", body.question.includes("bệnh") ? { reading_id: r.id, reason: "medical", text, disclaimer: DISCLAIMER } : { text })], false);
    return;
  }
  r.follow_ups_left -= 1;
  r.messages.push({ seq, question: body.question, answer: FOLLOW_UP.join(""), created_at: new Date().toISOString() });
  const frames = [
    ev("meta", { reading_id: r.id, kind: r.kind, topic: r.topic, follow_ups_left: r.follow_ups_left }),
    ...FOLLOW_UP.map((t) => ev("delta", { text: t })),
    ev("done", { reading_id: r.id, seq, disclaimer: DISCLAIMER }),
  ];
  await stream(res, frames, true);
}

export async function handle(req, res, body, send) {
  const path = (req.url ?? "").split("?")[0];
  if (req.method === "POST" && path === "/internal/qa/merge") {
    // Internal token only, never a bearer (contract).
    const want = process.env.STUB_INTERNAL_TOKEN;
    if ((want && req.headers["x-internal-token"] !== want) || req.headers.authorization) {
      send(res, 401, { status: 401 }, "application/problem+json");
      return true;
    }
    if (typeof body?.guest_id !== "string" || typeof body?.account_id !== "string") {
      send(res, 400, { status: 400 }, "application/problem+json");
      return true;
    }
    let moved = 0;
    for (const r of readings.values()) {
      if (r.subject === body.guest_id) {
        r.subject = body.account_id;
        moved += 1;
      }
    }
    send(res, 200, { moved });
    return true;
  }

  if (path === "/tuvi/qa/readings" || path.startsWith("/tuvi/qa/")) {
    const want = process.env.STUB_INTERNAL_TOKEN;
    const token = bearer(req);
    const who = identity.subjectOf(token);
    if ((want && req.headers["x-internal-token"] !== want) || !who) {
      send(res, 401, problem(401), "application/problem+json");
      return true;
    }
    const m = /^\/tuvi\/qa\/readings\/([^/]+)(\/follow-ups)?$/.exec(path);
    if (req.method === "POST" && path === "/tuvi/qa/readings") {
      await ask(req, res, body, send, who, token);
      return true;
    }
    if (req.method === "POST" && m?.[2]) {
      await followUp(res, m[1], body, send, who);
      return true;
    }
    if (req.method === "GET" && path === "/tuvi/qa/readings") {
      const mine = [...readings.values()].filter((r) => r.subject === who.id).sort((a, b) => b.created_at.localeCompare(a.created_at));
      send(res, 200, { readings: mine.map(summary) });
      return true;
    }
    if (req.method === "GET" && path === "/tuvi/qa/free-quota") {
      const used = freeUsed.get(who.id) ?? 0;
      send(res, 200, { limit: FREE_LIMIT, used, remaining: Math.max(0, FREE_LIMIT - used) });
      return true;
    }
    if (req.method === "GET" && m && !m[2]) {
      const r = readings.get(m[1]);
      if (!r || r.subject !== who.id) send(res, 404, problem(404), "application/problem+json");
      else send(res, 200, { ...summary(r), retry_until: r.retry_until, notice: r.notice, disclaimer: DISCLAIMER, messages: r.messages });
      return true;
    }
    if (req.method === "DELETE" && path === "/tuvi/qa/readings") {
      const mine = [...readings.values()].filter((r) => r.subject === who.id);
      if (mine.some((r) => ["PENDING", "RETRYING", "REFUND_PENDING"].includes(r.status))) {
        send(res, 409, problem(409, "reading_in_progress"), "application/problem+json");
        return true;
      }
      for (const r of mine) readings.delete(r.id);
      send(res, 204);
      return true;
    }
    return false;
  }

  const fixture = req.method === "POST" ? routes[path] : undefined;
  if (!fixture) return false;
  send(res, 200, fixture);
  return true;
}

import { describe, expect, it } from "vitest";
import { createSseParser } from "@/lib/sse";

function collect() {
  const events: { event: string; data: unknown }[] = [];
  return { events, parser: createSseParser((e) => events.push(e)) };
}

describe("createSseParser", () => {
  it("assembles one event split over three chunks", () => {
    const { events, parser } = collect();
    parser.push('event: del');
    parser.push('ta\ndata: {"text":"Xin ');
    expect(events).toEqual([]);
    parser.push('chào."}\n\n');
    expect(events).toEqual([{ event: "delta", data: { text: "Xin chào." } }]);
  });

  it("emits two events from one chunk, in order", () => {
    const { events, parser } = collect();
    parser.push('event: meta\ndata: {"follow_ups_left":2}\n\nevent: delta\ndata: {"text":"a"}\n\n');
    expect(events.map((e) => e.event)).toEqual(["meta", "delta"]);
  });

  it("accepts CRLF line endings, also split between the CR and the LF", () => {
    const { events, parser } = collect();
    parser.push('event: delta\r\ndata: {"text":"a"}\r');
    parser.push("\n\r\n");
    expect(events).toEqual([{ event: "delta", data: { text: "a" } }]);
  });

  it("ignores comment lines and unknown fields", () => {
    const { events, parser } = collect();
    parser.push(': keep-alive\nid: 7\nretry: 100\nevent: done\nwhatever: x\ndata: {"seq":0}\n\n');
    expect(events).toEqual([{ event: "done", data: { seq: 0 } }]);
  });

  it("joins multi-line data with a newline", () => {
    const { events, parser } = collect();
    parser.push('event: delta\ndata: {"text":\ndata: "a"}\n\n');
    expect(events).toEqual([{ event: "delta", data: { text: "a" } }]);
  });

  it("keeps a data value that is not JSON as a string; defaults the event name to message", () => {
    const { events, parser } = collect();
    parser.push("data: hello\n\n");
    expect(events).toEqual([{ event: "message", data: "hello" }]);
  });

  it("drops a partial event on end() but emits a complete one", () => {
    const { events, parser } = collect();
    parser.push('event: delta\ndata: {"text":"a"}\n\nevent: delta\ndata: {"te');
    parser.end();
    expect(events).toEqual([{ event: "delta", data: { text: "a" } }]);
  });

  it("emits nothing for a blank dispatch without data", () => {
    const { events, parser } = collect();
    parser.push("event: ping\n\n");
    expect(events).toEqual([]);
  });

  it("keeps Vietnamese intact across a chunk split inside a multibyte character", () => {
    const { events, parser } = collect();
    const bytes = new TextEncoder().encode('event: delta\ndata: {"text":"Lão Thiên Cơ luận ấn 印."}\n\n');
    const decoder = new TextDecoder("utf-8");
    // Cut after every 3 bytes: guaranteed to land inside 2- and 3-byte characters.
    for (let i = 0; i < bytes.length; i += 3) parser.push(decoder.decode(bytes.slice(i, i + 3), { stream: true }));
    parser.push(decoder.decode());
    expect(events).toEqual([{ event: "delta", data: { text: "Lão Thiên Cơ luận ấn 印." } }]);
  });
});

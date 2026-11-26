export type SseEvent = { event: string; data: unknown };

/**
 * Incremental Server-Sent Events parser (client-safe). Feed decoded text with `push`; each
 * complete event (ended by a blank line) is passed to `onEvent` with its `data` parsed as JSON
 * (kept as a string when it is not JSON). Handles events split across chunks, LF / CRLF / CR line
 * ends (also a CRLF split between chunks), comment lines and multi-line `data:`; unknown fields
 * are ignored. `end()` drops a partial event: the stream was cut, and half an event is not one.
 */
export function createSseParser(onEvent: (e: SseEvent) => void): { push(chunk: string): void; end(): void } {
  let buf = "";
  let event = "";
  let data: string[] = [];

  const dispatch = () => {
    const name = event || "message";
    const lines = data;
    event = "";
    data = [];
    if (lines.length === 0) return;
    const raw = lines.join("\n");
    let parsed: unknown = raw;
    try {
      parsed = JSON.parse(raw);
    } catch {
      /* catch-ok: not JSON, so the data stays text */
    }
    onEvent({ event: name, data: parsed });
  };

  const line = (l: string) => {
    if (l === "") return dispatch();
    if (l.startsWith(":")) return;
    const i = l.indexOf(":");
    const field = i < 0 ? l : l.slice(0, i);
    let value = i < 0 ? "" : l.slice(i + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "event") event = value;
    else if (field === "data") data.push(value);
  };

  return {
    push(chunk: string) {
      buf += chunk;
      let start = 0;
      for (let i = 0; i < buf.length; i++) {
        const c = buf[i];
        if (c !== "\n" && c !== "\r") continue;
        if (c === "\r") {
          if (i === buf.length - 1) break; // maybe the first half of CRLF: wait for the next chunk
          line(buf.slice(start, i));
          start = buf[i + 1] === "\n" ? i + 2 : i + 1;
          i = start - 1;
        } else {
          line(buf.slice(start, i));
          start = i + 1;
        }
      }
      buf = buf.slice(start);
    },
    end() {
      buf = "";
      event = "";
      data = [];
    },
  };
}

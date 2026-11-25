export type Level = "info" | "warn" | "error";

/** One JSON object per line on stdout (platform logging contract). No trace context yet: ids are empty. */
export function log(level: Level, message: string, fields: Record<string, string> = {}): void {
  const entry = {
    ...fields,
    timestamp: new Date().toISOString(),
    level,
    service: "web",
    trace_id: "",
    span_id: "",
    message,
  };
  process.stdout.write(`${JSON.stringify(entry)}\n`);
}

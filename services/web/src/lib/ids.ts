const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** A well-formed UUID (order and account ids); anything else is answered 404 before any upstream call. */
export function isUuid(v: unknown): v is string {
  return typeof v === "string" && UUID.test(v);
}

/** Today's calendar date (YYYY-MM-DD) in Vietnam, regardless of the server's zone. */
export function todayInVietnam(now = new Date()): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Ho_Chi_Minh" }).format(now);
}

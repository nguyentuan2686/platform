# A client-side streamed action leaves server-rendered state stale; a scaling animation overflows the page

- Date: 2026-11-26
- Tags: test | design

## Symptom
F1.5b Task 5 (Hỏi thầy). Vitest was green; the first Playwright run failed in five places:
1. After a paid question the header chip still said "Còn 1 lượt" (should be 0), and the free-chat button kept saying "còn 3 câu" after three free answers.
2. At 375 px the page scrolled sideways by 6 to 10 px right after an answer was sealed.
3. Two assertions used `getByRole("alert")` and hit two elements (the form's error and Next's `#__next-route-announcer__`).

## Root cause
1. The ask goes browser -> route handler -> SSE, not through a server action, so Next never revalidates the layout. The balance chip and the quota count are server components and kept the values of the page load.
2. `.seal-stamp` stamps in with `scale(2.3)`; while it runs its box is wider than the column and `scrollWidth` grows. `body { overflow-x: hidden }` does not stop `documentElement.scrollWidth`.
3. Next's route announcer is always an `role="alert"` node.

## Fix
1. `router.refresh()` once an ask ends in `done` or `queued` (a credit or a free chat was used). Client state (the streamed answer) survives a refresh. Unit tests assert it is called, and not for a refusal.
2. `.qa-result, .qa-messages` clip sideways (`overflow-x: clip`) inside a box one gutter wider than the column, so the seal still overhangs its bubble.
3. e2e locates form errors as `p[role=alert]`. The 375 px helper now lists the offending elements in its failure message.

## How to catch earlier
Any flow that changes server-rendered state from a route handler (not a server action) needs an e2e step that reads the header/balance afterwards. A 375 px check must also run while an entry animation is running, not only after it.

## Escalation
none (first occurrence)

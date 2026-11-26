# Next.js: module state is not shared between server actions and pages; React resets forms after an action

- Date: 2026-11-26
- Tags: test | design

## Symptom
F1.5b Task 4. Vitest was green, but Playwright on the production build failed in two places:
1. After "Thanh toán", the checkout page never showed the VietQR panel. Every visit missed the payment cache.
2. Pick "1 lượt", submit without ticking 18+ (refused), tick it, submit again: the order went out for 119.000đ (CREDIT_5) while the button said "Thanh toán 29.000đ".

Separately, two existing jsdom tests (BirthForm, Dialogue) timed out at Vitest's default 5 s while 9 more test files ran in parallel on the 2-core machine.

## Root cause
1. Next bundles server actions and RSC pages in different webpack layers, so a module-level `Map` in `paymentCache.ts` exists once per layer. `buyAction` wrote one copy and the page read the other. Unit tests import a single instance, so they cannot see this.
2. React 19 resets a `<form action={...}>` after the action runs. The controlled radios reverted in the DOM to their initially checked value (CREDIT_5), and the form serialised that value. React state (the button label) still held CREDIT_1.

## Fix
1. The cache map lives on `globalThis[Symbol.for("tuvi.web.paymentCache")]`. A unit test re-imports the module (`vi.resetModules`) and asserts the store is shared. It failed against the old code.
2. The submitted `product` is a hidden input bound to state. An effect re-checks the chosen radio after every action result. The e2e test asserts the radio stays checked after a refused submit.
3. `testTimeout: 20000` in vitest.config.ts.

## How to catch earlier
Any process-wide server state in services/web (caches, rate limits) goes on `globalThis` from the start. Any form with `useActionState` and controlled inputs needs an e2e step that submits after a refused submit. Run the e2e suite before calling a web task done, not only Vitest.

## Escalation
none (first occurrence)

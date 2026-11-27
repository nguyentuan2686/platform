# Web change pushed with three failing tests the dev machine never ran

- Date: 2026-11-27
- Tags: test | process

## Symptom
PR #43 (Tử vi 2027 pages) failed `web / verify` on three tests, then `web / e2e` on the sitemap count in `e2e/seo.spec.ts`: the existing `SiteFooter.test.tsx` link list, the
sitemap "no private URL" check (`/hoi\b/` matched the new slug `tuoi-hoi`), and a new content test (intro paragraph
without the reader voice "con").

## Root cause
Local vitest workers time out on the 2-core machine, so only lint and typecheck ran before the push. The footer
test was not looked up before editing the footer, and the private-URL regex matched a substring of a public slug.

## Fix
Updated the footer test, anchored the private-URL regex to whole path segments, added "con" to the intro.

## How to catch earlier
Before changing a web component or route list, `grep -rln "<Component|path|count>" services/web/src services/web/e2e` (unit tests and Playwright specs) and
update those tests in the same commit. Test regexes over URLs match whole segments (`/\/(a|b)(\/|$)/`), never bare
substrings.

## Escalation
none (first occurrence)

Self-hosted web fonts (SIL OFL 1.1, license texts next to the files). Source: the `@fontsource/charm`,
`@fontsource/ma-shan-zheng`, `@fontsource/noto-serif-display` and `@fontsource-variable/noto-serif` npm packages
(woff2 subsets vietnamese + latin, same unicode ranges as Google Fonts). Declared in `src/styles/fonts.css`.
Do not use `next/font/google`: it downloads at build time (guard: `src/fontPolicy.test.ts`).

import { dirname } from "path";
import { fileURLToPath } from "url";
import { FlatCompat } from "@eslint/eslintrc";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const compat = new FlatCompat({
  baseDirectory: __dirname,
});

const eslintConfig = [
  ...compat.extends("next/core-web-vitals", "next/typescript"),
  {
    // CLAUDE.md: never an empty catch (lesson 2026-11-26-swallowed-upstream-errors). The full check,
    // a catch that neither rethrows, logs nor says "catch-ok: <reason>", is src/catchPolicy.test.ts.
    rules: { "no-empty": ["error", { allowEmptyCatch: false }] },
  },
  {
    ignores: [
      "node_modules/**",
      ".next/**",
      "out/**",
      "build/**",
      "next-env.d.ts",
    ],
  },
];

export default eslintConfig;

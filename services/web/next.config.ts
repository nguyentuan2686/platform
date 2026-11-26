import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  // The share card reads its fonts from disk at request time: ship them in the standalone output.
  outputFileTracingIncludes: { "/vi/chia-se/[shareId]/the.png": ["./src/fonts/**/*"] },
  async headers() {
    return [
      {
        // The magic-link page carries a one-time token in its URL: never sent as a Referer.
        source: "/vi/dang-nhap/xac-nhan",
        headers: [{ key: "Referrer-Policy", value: "no-referrer" }],
      },
      {
        // The public chart page: never cached (a revoked link must stop at once), never indexed.
        source: "/vi/chia-se/:shareId",
        headers: [
          { key: "Cache-Control", value: "no-store" },
          { key: "X-Robots-Tag", value: "noindex, nofollow" },
        ],
      },
    ];
  },
};

export default nextConfig;

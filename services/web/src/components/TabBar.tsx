"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";

type Tab = { key: string; label: string; glyph: string; href: string; path: string; hash?: string; also?: string[] };

const TABS: Tab[] = [
  { key: "today", label: "Hôm nay", glyph: "今", href: "/vi/la-so#hom-nay", path: "/vi/la-so", hash: "#hom-nay" },
  { key: "chart", label: "Lá số", glyph: "命", href: "/vi/la-so", path: "/vi/la-so" },
  { key: "ask", label: "Hỏi thầy", glyph: "問", href: "/vi/hoi", path: "/vi/hoi", also: ["/vi/lich-su"] },
  { key: "account", label: "Tài khoản", glyph: "我", href: "/vi/tai-khoan", path: "/vi/tai-khoan" },
];

function useHash(): [string, (h: string) => void] {
  const [hash, setHash] = useState("");
  useEffect(() => {
    const read = () => setHash(window.location.hash);
    read();
    window.addEventListener("hashchange", read);
    return () => window.removeEventListener("hashchange", read);
  }, []);
  return [hash, setHash];
}

export function TabBar() {
  const pathname = usePathname();
  const [hash, setHash] = useHash();
  const isActive = (t: Tab) => {
    // A tab also owns the pages below it (a reading under /vi/hoi, transactions under /vi/tai-khoan).
    const own = (p: string) => pathname === p || pathname.startsWith(`${p}/`);
    if (!own(t.path) && !t.also?.some(own)) return false;
    if (t.hash) return hash === t.hash;
    // A page-level tab is current unless a sibling anchor tab on the same page is.
    return !TABS.some((o) => o.path === t.path && o.hash !== undefined && o.hash === hash);
  };
  return (
    <nav className="tabbar" aria-label="Điều hướng chính">
      {TABS.map((t) => (
        <Link
          key={t.key}
          href={t.href}
          className="tab"
          aria-current={isActive(t) ? "page" : undefined}
          onClick={() => setHash(t.hash ?? "")}
        >
          <span className="tab-glyph" aria-hidden="true">
            {t.glyph}
          </span>
          <span className="tab-label">{t.label}</span>
        </Link>
      ))}
    </nav>
  );
}

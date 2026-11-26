"use client";

import { useState } from "react";

/** Copies one payment field (account number, amount, content) to the clipboard. */
export function CopyButton({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      setCopied(false); // no clipboard permission: the value stays visible to copy by hand
    }
  }
  return (
    <button type="button" className="tag copy-button" onClick={copy} aria-label={`Sao chép ${label}`}>
      <span aria-live="polite">{copied ? "Đã chép" : "Chép"}</span>
    </button>
  );
}

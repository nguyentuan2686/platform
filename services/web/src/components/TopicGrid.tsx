"use client";

import type { Topic } from "@/lib/identity";
import { TOPICS } from "@/lib/topics";

/** The six topics as a two-column radio group; the glyph is set in Ma Shan Zheng. */
export function TopicGrid({
  value,
  onChange,
  disabled = false,
  describedBy,
}: {
  value: Topic | "";
  onChange: (t: Topic) => void;
  disabled?: boolean;
  describedBy?: string;
}) {
  return (
    <fieldset className="topic-grid" disabled={disabled} aria-describedby={describedBy}>
      <legend className="sr-only">Chủ đề</legend>
      {TOPICS.map((t) => (
        <label key={t.code} className="topic" data-on={t.code === value ? "true" : undefined}>
          <input
            type="radio"
            name="topic"
            value={t.code}
            checked={t.code === value}
            onChange={() => onChange(t.code)}
          />
          <span className="topic-glyph" aria-hidden="true">
            {t.glyph}
          </span>
          <span className="topic-text">
            <span className="topic-label">{t.label}</span>
            <span className="topic-hint">{t.hint}</span>
          </span>
        </label>
      ))}
    </fieldset>
  );
}

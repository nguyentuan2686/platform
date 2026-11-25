import { randomUUID } from "node:crypto";
import type { Birth } from "@/lib/types";

type Entry = { birth: Birth; expires: number };

/** In-memory birth store: random 122-bit id, TTL, LRU eviction. Never persisted. */
export class BirthStore {
  private readonly map = new Map<string, Entry>();
  constructor(
    private readonly ttlMs = 2 * 60 * 60 * 1000,
    private readonly max = 1000,
  ) {}

  put(b: Birth): string {
    const id = randomUUID();
    this.map.set(id, { birth: b, expires: Date.now() + this.ttlMs });
    while (this.map.size > this.max) {
      const oldest = this.map.keys().next().value as string;
      this.map.delete(oldest);
    }
    return id;
  }

  get(id: string): Birth | undefined {
    const e = this.map.get(id);
    if (!e) return undefined;
    if (e.expires <= Date.now()) {
      this.map.delete(id);
      return undefined;
    }
    // LRU: move to the most-recent end.
    this.map.delete(id);
    this.map.set(id, e);
    return e.birth;
  }
}

const g = globalThis as unknown as { __birthStore?: BirthStore };
/** Process-wide singleton (survives Next module re-evaluation). */
export const birthStore: BirthStore = (g.__birthStore ??= new BirthStore());

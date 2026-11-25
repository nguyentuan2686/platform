import { describe, expect, it } from "vitest";
import { cellOf } from "@/lib/boardLayout";

describe("cellOf", () => {
  it.each([
    ["Si", 1, 1],
    ["Wu", 1, 2],
    ["Wei", 1, 3],
    ["Shen", 1, 4],
    ["Chen", 2, 1],
    ["You", 2, 4],
    ["Mao", 3, 1],
    ["Xu", 3, 4],
    ["Yin", 4, 1],
    ["Chou", 4, 2],
    ["Zi", 4, 3],
    ["Hai", 4, 4],
  ] as const)("%s -> row %i col %i", (b, row, col) => {
    expect(cellOf(b)).toEqual({ row, col });
  });
});

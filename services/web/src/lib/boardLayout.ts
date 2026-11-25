import type { Branch } from "@/lib/types";

export type Cell = { row: number; col: number };

/** Traditional tử vi layout: Tỵ Ngọ Mùi Thân on top, Dần Sửu Tý Hợi at the bottom, centre 2x2 free. */
const CELLS: Record<Branch, Cell> = {
  Si: { row: 1, col: 1 },
  Wu: { row: 1, col: 2 },
  Wei: { row: 1, col: 3 },
  Shen: { row: 1, col: 4 },
  Chen: { row: 2, col: 1 },
  You: { row: 2, col: 4 },
  Mao: { row: 3, col: 1 },
  Xu: { row: 3, col: 4 },
  Yin: { row: 4, col: 1 },
  Chou: { row: 4, col: 2 },
  Zi: { row: 4, col: 3 },
  Hai: { row: 4, col: 4 },
};

export function cellOf(branch: Branch): Cell {
  return CELLS[branch];
}

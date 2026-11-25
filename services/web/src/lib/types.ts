export type Gender = "male" | "female";
/** Birth data. Lives only in server memory; never logged and never put in a URL. */
export type Birth = { birth: string; zone: string; gender: Gender; calendar: "VN" };

export type Branch =
  | "Zi"
  | "Chou"
  | "Yin"
  | "Mao"
  | "Chen"
  | "Si"
  | "Wu"
  | "Wei"
  | "Shen"
  | "You"
  | "Xu"
  | "Hai";

export type Star = { key: string; nameVi: string; brightness: string | null; mutagen: string | null };
export type AdjStar = { key: string; nameVi: string };
export type Palace = {
  key: string;
  nameVi: string;
  branch: Branch;
  stem: string;
  isBody: boolean;
  decade: { from: number; to: number };
  major: Star[];
  minor: Star[];
  adjective: AdjStar[];
  smallLimitAges: number[];
  tuan: boolean;
  triet: boolean;
};
export type Chart = {
  school: string;
  calendar: "VN" | "CN";
  lunar: { year: number; month: number; day: number; leap: boolean };
  yearCanChi: { stem: string; branch: Branch; nameVi: string };
  hourBranch: Branch;
  cuc: { element: string; number: number; nameVi: string };
  menhChu: string;
  thanChu: string;
  thanPalace: string;
  palaces: Palace[];
  tuan: Branch[];
  triet: Branch[];
};

export type Section = { id: string; title?: string; lines: string[] };
export type BasicReading = {
  character: "LaoThienCo";
  school: string;
  chartHash: string;
  knowledgeVersion: number;
  sections: Section[];
};

export type Level = "Thuan" | "Binh" | "LuuY";
export type TodayArea = { area: string; label: string; level: Level; line: string };
export type TodayReading = {
  date: string;
  lunar: string;
  dayCanChi: string;
  character: "LaoThienCo";
  opening: string;
  areas: TodayArea[];
  closing: string;
};

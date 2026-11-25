"use server";

import { redirect } from "next/navigation";
import { parseBirthForm, type FormErrors } from "@/lib/birthForm";
import { birthStore } from "@/lib/birthStore";
import { getChart, UpstreamError, UpstreamValidation } from "@/lib/upstream";

export type FormState = { errors: Partial<FormErrors>; unavailable?: boolean } | null;

const FIELD_MAP: Record<string, keyof FormErrors> = { birth: "date", zone: "zone", gender: "gender" };
const FIELD_MSG: Record<keyof FormErrors, string> = {
  date: "Ngày giờ sinh con nhập chưa hợp lệ.",
  time: "Giờ sinh con nhập chưa hợp lệ.",
  gender: "Con hãy chọn giới tính.",
  zone: "Nơi sinh con chọn chưa hợp lệ.",
};

export async function submitBirth(_prev: FormState, fd: FormData): Promise<FormState> {
  const parsed = parseBirthForm(fd);
  if (!parsed.ok) return { errors: parsed.errors };
  // Validate upstream before issuing an id, so e.g. a daylight-saving gap shows as a form error.
  try {
    await getChart(parsed.birth);
  } catch (e) {
    if (e instanceof UpstreamValidation) {
      const errors: Partial<FormErrors> = {};
      for (const f of Object.keys(e.fields)) {
        const k = FIELD_MAP[f];
        if (k) errors[k] = FIELD_MSG[k];
      }
      if (Object.keys(errors).length === 0) errors.date = FIELD_MSG.date;
      return { errors };
    }
    if (e instanceof UpstreamError) return { errors: {}, unavailable: true };
    throw e;
  }
  const id = birthStore.put(parsed.birth);
  redirect(`/vi/la-so/${id}`);
}

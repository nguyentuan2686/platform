"use server";

import { redirect } from "next/navigation";
import { parseBirthForm, type FormErrors } from "@/lib/birthForm";
import { getProfile, putProfile, type Profile, type ProfileRequest } from "@/lib/identity";
import { clearSessionCookie, ensureSession, readSessionToken } from "@/lib/session";
import { getChart, UpstreamError, UpstreamProblem, UpstreamValidation } from "@/lib/upstream";

export type FormValues = { date: string; time: string; gender: string; zone: string; consent?: string };
export type FormState = { errors: Partial<FormErrors>; unavailable?: boolean; values?: FormValues } | null;

const DEFAULT_LABEL = "Lá số của tôi";
const LOCKED_MSG = "Lá số của con đã khóa sau lượt hỏi đầu tiên, không đổi ngày giờ sinh được nữa.";

function submitted(fd: FormData): FormValues {
  const s = (k: string) => {
    const v = fd.get(k);
    return typeof v === "string" ? v : "";
  };
  return { date: s("date"), time: s("time"), gender: s("gender"), zone: s("zone"), consent: s("consent") };
}

const FIELD_MAP: Record<string, keyof FormErrors> = {
  birth: "date",
  "birth.birth": "date",
  zone: "zone",
  "birth.zone": "zone",
  gender: "gender",
  "birth.gender": "gender",
};
const FIELD_MSG: Record<"date" | "time" | "gender" | "zone", string> = {
  date: "Ngày giờ sinh con nhập chưa hợp lệ.",
  time: "Giờ sinh con nhập chưa hợp lệ.",
  gender: "Con hãy chọn giới tính.",
  zone: "Nơi sinh con chọn chưa hợp lệ.",
};

/** The profile of the current cookie, if any. Never creates a session. */
async function currentProfile(): Promise<Profile | null> {
  const token = await readSessionToken();
  if (!token) return null;
  try {
    return await getProfile(token);
  } catch (e) {
    if (e instanceof UpstreamProblem && e.status === 401) return null; // stale cookie: renewed on save
    throw e;
  }
}

/** Saves the profile under the session, creating a guest lazily; renews a rejected cookie once. */
async function saveProfile(req: ProfileRequest): Promise<void> {
  const token = await ensureSession();
  try {
    await putProfile(token, req);
  } catch (e) {
    if (!(e instanceof UpstreamProblem && e.status === 401)) throw e;
    await clearSessionCookie();
    await putProfile(await ensureSession({ renew: true }), req);
  }
}

export async function submitBirth(_prev: FormState, fd: FormData): Promise<FormState> {
  const values = submitted(fd);
  const parsed = parseBirthForm(fd);
  if (!parsed.ok) return { errors: parsed.errors, values };
  try {
    const existing = await currentProfile();
    const calendar = existing?.calendar ?? "VN";
    const { birth, zone, gender } = parsed.birth;
    // Validate with chart-svc before saving, so e.g. a daylight-saving gap shows as a form error.
    await getChart({ birth, zone, gender, calendar });
    await saveProfile({ label: existing?.label ?? DEFAULT_LABEL, birth: { birth, zone, gender }, calendar });
  } catch (e) {
    if (e instanceof UpstreamValidation) {
      const errors: Partial<FormErrors> = {};
      for (const f of Object.keys(e.fields)) {
        const k = FIELD_MAP[f];
        if (k && k !== "consent") errors[k] = FIELD_MSG[k];
      }
      if (Object.keys(errors).length === 0) errors.date = FIELD_MSG.date;
      return { errors, values };
    }
    if (e instanceof UpstreamProblem && e.status === 409 && e.reason === "profile_locked") {
      return { errors: { date: LOCKED_MSG }, values };
    }
    if (e instanceof UpstreamError || e instanceof UpstreamProblem) return { errors: {}, unavailable: true, values };
    throw e;
  }
  redirect("/vi/la-so");
}

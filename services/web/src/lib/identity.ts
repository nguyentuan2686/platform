import "server-only";
import { callJson, UpstreamProblem } from "@/lib/http";
import type { Calendar, Gender } from "@/lib/types";

// Types mirror contracts/openapi/identity-svc.yaml.
export type GuestSession = { session_token: string; guest_id: string; expires_at: string };
export type Me =
  | { kind: "guest"; guest_id: string }
  | { kind: "account"; account_id: string; email_masked?: string };
export type ProfileBirth = { birth: string; zone: string; gender: Gender };
export type ProfileRequest = { label: string; birth: ProfileBirth; calendar: Calendar };
export type Profile = { id: string; label: string; birth: ProfileBirth; calendar: Calendar; locked: boolean };
export type PassKind = "MONTH" | "YEAR";
export type Entitlements = {
  credits: number;
  pass: { kind: PassKind; ends_at: string; questions_left: number | null } | null;
  features: ("daily_detail" | "yearly_reading")[];
};
export type Topic = "TinhDuyen" | "SuNghiep" | "TaiLoc" | "SucKhoe" | "GiaDao" | "VanNam";
export type LedgerEntry = {
  delta: number;
  source: "CREDIT" | "PASS";
  reason: "signup_bonus" | "purchase" | "consume" | "refund" | "reversal";
  topic?: Topic | null;
  created_at: string;
};
export type LoginSession = { session_token: string; account_id: string; expires_at: string; created: boolean };
export type MagicLinkAccepted = { status: "sent_if_allowed" };
export type GoogleAuthorization = { authorization_url: string; state: string };
export type ShareLink = { share_id: string; created_at: string };
/** Full decrypted birth: server-only; a public page may show only gender, giờ and năm can chi. */
export type SharedProfile = { birth: ProfileBirth; calendar: Calendar };

const SHARE_ID = /^[A-Za-z0-9_-]{22}$/;

async function orNull<T>(p: Promise<T>): Promise<T | null> {
  try {
    return await p;
  } catch (e) {
    if (e instanceof UpstreamProblem && e.status === 404) return null;
    throw e;
  }
}

export function createGuest(): Promise<GuestSession> {
  return callJson<GuestSession>("identity", "/guests", { method: "POST" });
}
export function me(bearer: string): Promise<Me> {
  return callJson<Me>("identity", "/me", { bearer });
}

/** The caller's profile, or null when there is none yet. */
export function getProfile(bearer: string): Promise<Profile | null> {
  return orNull(callJson<Profile>("identity", "/me/profile", { bearer }));
}
/** Throws UpstreamProblem(409, "profile_locked") when a locked birth would change. */
export function putProfile(bearer: string, req: ProfileRequest): Promise<Profile> {
  return callJson<Profile>("identity", "/me/profile", { method: "PUT", bearer, body: req });
}
export function deleteProfile(bearer: string): Promise<void> {
  return callJson<void>("identity", "/me/profile", { method: "DELETE", bearer });
}

export function entitlements(bearer: string): Promise<Entitlements> {
  return callJson<Entitlements>("identity", "/me/entitlements", { bearer });
}
export function ledger(bearer: string): Promise<LedgerEntry[]> {
  return callJson<LedgerEntry[]>("identity", "/me/ledger", { bearer });
}

export function requestMagicLink(email: string, guestBearer?: string): Promise<MagicLinkAccepted> {
  return callJson<MagicLinkAccepted>("identity", "/auth/magic-links", {
    method: "POST",
    bearer: guestBearer,
    body: { email, lang: "vi" },
  });
}
export function verifyMagicLink(token: string, guestBearer?: string): Promise<LoginSession> {
  return callJson<LoginSession>("identity", "/auth/magic-links/verify", {
    method: "POST",
    bearer: guestBearer,
    body: { token },
  });
}

export function googleAuthorization(redirectUri: string, guestBearer?: string): Promise<GoogleAuthorization> {
  return callJson<GoogleAuthorization>(
    "identity",
    `/auth/google/authorization?redirect_uri=${encodeURIComponent(redirectUri)}`,
    { bearer: guestBearer },
  );
}
export function googleCallback(code: string, state: string, guestBearer?: string): Promise<LoginSession> {
  return callJson<LoginSession>("identity", "/auth/google/callback", {
    method: "POST",
    bearer: guestBearer,
    body: { code, state },
  });
}

export function logout(bearer: string): Promise<void> {
  return callJson<void>("identity", "/sessions/current", { method: "DELETE", bearer });
}

/** The active share link, or null. */
export function getShare(bearer: string): Promise<ShareLink | null> {
  return orNull(callJson<ShareLink>("identity", "/me/profile/share", { bearer }));
}
/** Idempotent; UpstreamProblem(409, "no_profile") without a profile. */
export function createShare(bearer: string): Promise<ShareLink> {
  return callJson<ShareLink>("identity", "/me/profile/share", { method: "PUT", bearer });
}
export function deleteShare(bearer: string): Promise<void> {
  return callJson<void>("identity", "/me/profile/share", { method: "DELETE", bearer });
}
/** Resolves a public share id (internal token only); null for a malformed, unknown or revoked id. */
export async function resolveShare(shareId: string): Promise<SharedProfile | null> {
  if (!SHARE_ID.test(shareId)) return null;
  return orNull(callJson<SharedProfile>("identity", `/shares/${shareId}`));
}

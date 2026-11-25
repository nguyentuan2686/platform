"use client";

import { useActionState } from "react";
import { submitBirth, type FormState } from "@/app/vi/actions";
import { GENDER_LABELS, ZONES } from "@/lib/labels";

function FieldError({ id, message }: { id: string; message?: string }) {
  if (!message) return null;
  return (
    <p id={id} className="field-error">
      {message}
    </p>
  );
}

export function BirthForm({ initialState = null }: { initialState?: FormState }) {
  const [state, action, pending] = useActionState(submitBirth, initialState);
  const errors = state?.errors ?? {};
  const err = (k: keyof typeof errors) => ({
    "aria-invalid": errors[k] ? true : undefined,
    "aria-describedby": errors[k] ? `err-${k}` : undefined,
  });

  return (
    <form action={action} className="slip" noValidate>
      {state?.unavailable && (
        <p role="alert" className="form-alert">
          Lão Thiên Cơ đang cần một lát để xem lại thiên cơ. Con chờ chút rồi thử lại nhé.
        </p>
      )}
      <div className="row2">
        <div className="field">
          <label htmlFor="date">Ngày sinh (dương lịch)</label>
          <input id="date" name="date" type="date" min="1900-01-01" max="2100-12-31" required className="in" {...err("date")} />
          <FieldError id="err-date" message={errors.date} />
        </div>
        <div className="field">
          <label htmlFor="time">Giờ sinh</label>
          <input id="time" name="time" type="time" required className="in" {...err("time")} />
          <FieldError id="err-time" message={errors.time} />
        </div>
      </div>
      <fieldset className="field" aria-describedby={errors.gender ? "err-gender" : undefined}>
        <legend>Giới tính</legend>
        <div className="seg">
          {(Object.keys(GENDER_LABELS) as (keyof typeof GENDER_LABELS)[]).map((g) => (
            <span key={g}>
              <input id={`gender-${g}`} type="radio" name="gender" value={g} required />
              <label htmlFor={`gender-${g}`}>{GENDER_LABELS[g]}</label>
            </span>
          ))}
        </div>
        <FieldError id="err-gender" message={errors.gender} />
      </fieldset>
      <div className="field">
        <label htmlFor="zone">Nơi sinh</label>
        <select id="zone" name="zone" defaultValue="Asia/Ho_Chi_Minh" className="in" {...err("zone")}>
          {ZONES.map((z) => (
            <option key={z.value} value={z.value}>
              {z.label}
            </option>
          ))}
        </select>
        <FieldError id="err-zone" message={errors.zone} />
      </div>
      <button type="submit" className="btn" disabled={pending}>
        <span className="stamp stamp-sm" aria-hidden="true">
          命
        </span>
        Xin Lão Thiên Cơ xem giúp
      </button>
      <p className="fine">Không cần đăng nhập. Ngày giờ sinh không được dùng vào việc khác.</p>
    </form>
  );
}

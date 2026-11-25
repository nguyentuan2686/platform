"use client";

export default function ViError({ reset }: { error: Error; reset: () => void }) {
  return (
    <main>
      <h1>Lão Thiên Cơ cần một lát</h1>
      <p>Ta đang cần chút thời gian để xem lại thiên cơ. Con chờ một lát rồi thử lại nhé.</p>
      <button type="button" onClick={reset}>
        Thử lại
      </button>
    </main>
  );
}

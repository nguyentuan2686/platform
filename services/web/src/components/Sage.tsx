export type SageMood = "greet" | "read";

/** Lão Thiên Cơ, ported from the prototype sage() function. */
export function Sage({ mood, className }: { mood: SageMood; className?: string }) {
  const eyes = mood === "read" ? "M50 62q3 1.5 6 0M64 62q3 1.5 6 0" : "M50 61q3 2.5 6 0M64 61q3 2.5 6 0";
  return (
    <svg viewBox="0 0 120 140" role="img" aria-label="Lão Thiên Cơ" className={className} data-mood={mood}>
      <circle cx="60" cy="58" r="50" fill="#C8A04A" fillOpacity=".16" />
      <circle cx="60" cy="58" r="50" fill="none" stroke="#C8A04A" strokeOpacity=".5" />
      <path d="M12 140C16 104 36 90 60 90S104 104 108 140Z" fill="#6B1D15" />
      <path d="M12 140C16 104 36 90 60 90S104 104 108 140" fill="none" stroke="#C8A04A" strokeWidth="1.2" />
      <path d="M44 93L60 128L76 93" fill="none" stroke="#C8A04A" strokeWidth="2" />
      <ellipse cx="60" cy="60" rx="17" ry="20" fill="#E6C9A0" />
      <path d="M42 58C41 38 79 38 78 58C73 49 47 49 42 58Z" fill="#EDE7DB" />
      <circle cx="60" cy="32" r="8" fill="#EDE7DB" />
      <line x1="47" y1="33" x2="74" y2="29" stroke="#C8A04A" strokeWidth="2" strokeLinecap="round" />
      <path d="M47 56q-7 2-10 10M73 56q7 2 10 10" fill="none" stroke="#F5F1E8" strokeWidth="2.4" strokeLinecap="round" />
      <path d={eyes} fill="none" stroke="#3A2618" strokeWidth="1.4" strokeLinecap="round" />
      <path d="M45 69C45 92 52 114 60 128C68 114 75 92 75 69C69 76 51 76 45 69Z" fill="#F2EDE3" />
      <path d="M51 72q9-5 18 0q-9 3-18 0Z" fill="#E3DCCF" />
      <path d="M60 80v40M55 84q2 18 5 34M65 84q-2 18-5 34" stroke="#D8D0C2" strokeWidth=".8" fill="none" />
      <path d="M100 96v6" stroke="#C8A04A" strokeWidth="1" />
      <circle cx="100" cy="105" r="4.2" fill="#B07A2E" />
      <circle cx="100" cy="115" r="7" fill="#B07A2E" />
      <path d="M96 103q4-2 8 0" stroke="#7a1d14" strokeWidth="1.6" fill="none" />
      <path d="M22 122q8-16 20-18l4 12q-12 4-24 6Z" fill="#7d2318" stroke="#C8A04A" strokeWidth=".8" />
      <path d="M98 122q-8-16-20-18l-4 12q12 4 24 6Z" fill="#7d2318" stroke="#C8A04A" strokeWidth=".8" />
      <rect x="33" y="99" width="54" height="19" fill="#F1E6CC" stroke="#C8A04A" strokeWidth=".6" />
      <g stroke="#A8231B" strokeWidth=".6" strokeOpacity=".8">
        <path d="M46.5 99v19M60 99v19M73.5 99v19M33 105.3h54M33 111.6h54" />
      </g>
      <rect x="50" y="104" width="20" height="9" fill="#F1E6CC" />
      <text x="60" y="111.2" textAnchor="middle" fontFamily="var(--glyph), serif" fontSize="7" fill="#A8231B">
        命盤
      </text>
      <rect x="29" y="96" width="5" height="25" rx="2" fill="#5A2E14" />
      <rect x="86" y="96" width="5" height="25" rx="2" fill="#5A2E14" />
      <ellipse cx="31.5" cy="110" rx="4.5" ry="5" fill="#E6C9A0" />
      <ellipse cx="88.5" cy="110" rx="4.5" ry="5" fill="#E6C9A0" />
      <text x="96" y="30" fontFamily="var(--glyph), serif" fontSize="16" fill="#A8231B" fillOpacity=".8">
        命
      </text>
    </svg>
  );
}

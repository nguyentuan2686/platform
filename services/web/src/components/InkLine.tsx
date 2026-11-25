import { memo } from "react";

/** One line of speech; each word fades from a blurred ink-bleed to sharp once its turn has come. */
function InkLineBase({ words, count }: { words: string[]; count: number }) {
  return (
    <>
      {words.map((w, i) => (
        <span key={i}>
          {i > 0 && " "}
          <span className="w" data-on={i < count ? "true" : "false"}>
            {w}
          </span>
        </span>
      ))}
    </>
  );
}

export const InkLine = memo(InkLineBase);

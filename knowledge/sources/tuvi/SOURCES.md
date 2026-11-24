# Tử vi sources (public domain only)

Raw source text for the F1.2b knowledge compilation. These files are reference data for the
compiler (Claude, at build time); they are never shipped to or read by any service at runtime.
Regenerate with `tools/knowledge/fetch-wikisource.sh` (idempotent; prints the revision ids).

Work: 紫微斗數全書 (Ming dynasty, attributed to 陳希夷, ed. 羅洪先), transcription on zh.wikisource.

| File | Title | URL | Revision id | Fetched |
|---|---|---|---|---|
| `ziwei-quanshu/juan1.txt` | 紫微斗數全書/卷一 | https://zh.wikisource.org/wiki/紫微斗數全書/卷一 | 7913704 | 2026-11-24 |
| `ziwei-quanshu/juan2.txt` | 紫微斗數全書/卷二 | https://zh.wikisource.org/wiki/紫微斗數全書/卷二 | 1963110 | 2026-11-24 |
| `ziwei-quanshu/juan3.txt` | 紫微斗數全書/卷三 | https://zh.wikisource.org/wiki/紫微斗數全書/卷三 | 2268626 | 2026-11-24 |

`license_basis`: "PD-old: Ming-dynasty text (author died > 400 years ago); Wikisource transcription of a PD work"

## Processing

- Fetched through the MediaWiki API (`action=parse&prop=wikitext|revid`, `formatversion=2`).
- Markup stripped to plain text: templates, comments, refs, tables, file/category links removed;
  links reduced to their text; section headings kept as `== ... ==` lines (the number of `=` follows
  the wiki heading level).
- The pages are single pages (no subpages). The transcription mixes traditional (juan 1 up to the
  star Q&A) and simplified characters (the rest of juan 1, juan 2, juan 3); `source_ref` uses the
  heading exactly as it appears in the file.
- No other source (modern books, translations, commentaries, websites) is used.

#!/usr/bin/env bash
# Fetch the public-domain 紫微斗數全書 (juan 1-3) from zh.wikisource as plain text.
# Output: knowledge/sources/tuvi/ziwei-quanshu/juan{1,2,3}.txt (UTF-8, section headings kept as "== ... ==").
# Prints "<file> revid=<id> title=<title>" per page; copy the ids into knowledge/sources/tuvi/SOURCES.md.
# Idempotent: re-running rewrites identical files while the revision is unchanged.
# Needs: bash, curl, perl (JSON::PP is in perl core).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/knowledge/sources/tuvi/ziwei-quanshu"
API="https://zh.wikisource.org/w/api.php"
UA="platform-knowledge-fetch/1.0"
mkdir -p "$OUT"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fetch() { # <n> <title>
  local n="$1" title="$2"
  # Native curl on Windows mangles non-ASCII arguments, so percent-encode the title ourselves.
  local enc
  enc="$(perl -e 'my $s=$ARGV[0]; $s=~s/([^A-Za-z0-9_.~-])/sprintf("%%%02X",ord($1))/ge; print $s' "$title")"
  curl -fsS -A "$UA" -o "$TMP/p$n.json" \
    "$API?action=parse&prop=wikitext%7Crevid&format=json&formatversion=2&page=$enc"
  PERL_UNICODE=SDA perl -MJSON::PP -e '
    local $/; open my $f, "<:raw", $ARGV[0] or die; my $d = decode_json(<$f>);
    die "no page: $ARGV[0]" unless $d->{parse};
    binmode STDOUT, ":utf8";
    print "REVID=$d->{parse}{revid}\n---\n$d->{parse}{wikitext}\n";' "$TMP/p$n.json" > "$TMP/w$n.txt"
  local revid; revid="$(sed -n '1s/^REVID=//p' "$TMP/w$n.txt")"
  # Strip markup: comments, refs, templates (iterative for nesting), tables, tags, links, emphasis.
  sed '1,2d' "$TMP/w$n.txt" | PERL_UNICODE=SDA perl -0777 -pe '
    s/<!--.*?-->//gs;
    s/<ref[^>]*\/>//gs; s/<ref[^>]*>.*?<\/ref>//gs;
    1 while s/\{\{[^{}]*\}\}//gs;
    s/\{\|.*?\|\}//gs;
    s/\[\[(?:File|Image|檔案|文件|Category|分類):[^\]]*\]\]//gi;
    s/\[\[[^\]|]*\|([^\]]*)\]\]/$1/g; s/\[\[([^\]]*)\]\]/$1/g;
    s/\[https?:[^\s\]]*\s*([^\]]*)\]/$1/g;
    s/<[^>]+>//g;
    s/\x27{2,}//g;
    s/[ \t]+$//mg; s/\n{3,}/\n\n/g;' > "$OUT/juan$n.txt"
  echo "juan$n.txt revid=$revid title=$title"
}

fetch 1 "紫微斗數全書/卷一"
fetch 2 "紫微斗數全書/卷二"
fetch 3 "紫微斗數全書/卷三"
echo "fetched $(date -u +%F)"

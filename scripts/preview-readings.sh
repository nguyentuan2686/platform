#!/usr/bin/env bash
# Usage: scripts/preview-readings.sh [READING_URL] [OUT_FILE]   (curl + perl JSON::PP, no jq)
# Renders three full readings (basic + today) to Markdown for the product owner to read.
# Warnings go to stderr: sections where >3 lines share the same first two words, and the
# number of `missing`-frame lines.
set -euo pipefail
BASE="${1:-http://localhost:8083}"
OUT="${2:-docs/tuvi/sample-readings.md}"
TODAY="$(date +%F)"
fail() { echo "FAIL: $*" >&2; exit 1; }

post() { # PATH BODY -> stdout JSON
  local resp status
  resp=$(curl -sS -w '\n%{http_code}' -X POST "$BASE$1" -H 'Content-Type: application/json' -d "$2") || fail "POST $1 unreachable"
  status=$(printf '%s' "$resp" | tail -n1)
  [ "$status" = "200" ] || fail "POST $1 expected 200, got '$status'"
  printf '%s' "$resp" | sed '$d'
}

PERL_RENDER='
use strict; use warnings; use utf8; use JSON::PP;
binmode(STDIN, ":raw"); binmode(STDOUT, ":utf8"); binmode(STDERR, ":utf8");
my ($mode, $label) = @ARGV;
local $/; my $d = JSON::PP->new->utf8->decode(<STDIN>);
my $MISSING = "Điều này ta chưa lật";
my $speaker = "**Lão Thiên Cơ:**";
my $missing = 0;
my %LEVEL = (Thuan => "Thuận", Binh => "Bình", LuuY => "Lưu ý");
if ($mode eq "basic") {
  for my $s (@{ $d->{sections} }) {
    print "### $s->{title}\n\n" if defined $s->{title};
    my %first;
    for my $l (@{ $s->{lines} }) {
      print "$speaker $l\n\n";
      $missing++ if index($l, $MISSING) == 0;
      my @w = split /\s+/, $l; my $k = lc join(" ", @w[0..($#w>0?1:0)]);
      $first{$k}++;
    }
    for my $k (sort keys %first) {
      print STDERR "WARN [$label] section $s->{id}: $first{$k} lines start with \"$k\"\n" if $first{$k} > 3;
    }
  }
  print STDERR "INFO [$label] missing lines: $missing\n";
} else {
  print "### Hôm nay ($d->{date}, âm lịch $d->{lunar}, ngày $d->{dayCanChi})\n\n";
  print "$speaker $d->{opening}\n\n";
  for my $a (@{ $d->{areas} }) {
    my $lv = $LEVEL{$a->{level}} // $a->{level};
    print "- **$a->{label}** ($lv): $a->{line}\n";
    $missing++ if index($a->{line}, $MISSING) == 0;
  }
  print "\n$speaker $d->{closing}\n\n";
  print STDERR "INFO [$label] today missing lines: $missing\n";
}
'

CHI=(Tý Sửu Dần Mão Thìn Tỵ Ngọ Mùi Thân Dậu Tuất Hợi)
CAN=(Canh Tân Nhâm Quý Giáp Ất Bính Đinh Mậu Kỷ)
# NOTE: the year can chi is computed from the solar (Gregorian) year, not the lunar year, so it is
# only right for births after Tết; the three sample dates below are all post-Tết. Do not reuse
# for January/early-February births.
header() { # BIRTH GENDER -> header line (hour branch + year only; no full date)
  local birth="$1" gender="$2" y h hr g
  y=${birth:0:4}; h=$((10#${birth:11:2}))
  hr=${CHI[$(( ((h + 1) / 2) % 12 ))]}
  [ "$gender" = male ] && g=Nam || g=Nữ
  echo "$g, sinh giờ $hr năm ${CAN[$((y % 10))]} ${CHI[$(( (y + 8) % 12 ))]}"
}

{
  echo "# Bản xem thử Tử Vi (F1.2b)"
  echo
  echo "Tạo bởi \`scripts/preview-readings.sh\` ngày $TODAY. Ba lá số mẫu; năm ghi theo năm dương lịch quy đổi can chi (các ngày mẫu đều sau Tết)."
  echo
} > "$OUT"

n=0
for spec in "1994-03-12T08:30 male" "1988-10-05T23:40 female" "2001-07-20T14:15 female"; do
  n=$((n + 1)); birth=${spec% *}; gender=${spec#* }
  body="\"birth\":\"$birth\",\"zone\":\"Asia/Ho_Chi_Minh\",\"gender\":\"$gender\",\"calendar\":\"VN\""
  label="chart$n"
  { echo "## Lá số $n"; echo; echo "_$(header "$birth" "$gender")_"; echo; } >> "$OUT"
  post /tuvi/readings/basic "{$body}" | perl -e "$PERL_RENDER" basic "$label" >> "$OUT"
  post /tuvi/readings/today "{$body,\"date\":\"$TODAY\"}" | perl -e "$PERL_RENDER" today "$label" >> "$OUT"
done
echo "Wrote $OUT" >&2

#!/usr/bin/env bash
# shellcheck disable=SC2016 # literal ${...} in expected file contents, intentional
# Tests for scripts/new-service.sh, offline: renders a Java and a Rust service into a scratch git copy of the working
# tree (tracked + untracked, not ignored) and checks the files, every registration (exactly once), the refusals
# (second run, bad name/port/lang, used port, missing marker) and that a refused run leaves the tree untouched.
# Review Focus: no half-edits, CRLF shared files, ci.yml stays valid (actionlint when installed, as in ops-scripts).
# Prints "ok <case>" per case; exits non-zero on the first failure.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
scr="$tmp/repo"
owner=ghcr.io/nguyentuan2686
token_re='__(SERVICE|JAVA_PKG|CRATE|PORT|CLASS|ENV)__'

fail() { echo "FAIL $1: $2" >&2; exit 1; }

# Scratch copy of the working tree, committed so `git status` shows exactly what a run changed.
mkdir -p "$scr"
files="$(git -C "$root" ls-files --cached --others --exclude-standard)"
(cd "$root" && printf '%s\n' "$files" | while IFS= read -r f; do [ -f "$f" ] && printf '%s\n' "$f"; done | tar -cf - -T -) \
  | tar -xf - -C "$scr"
g() { git -C "$scr" -c user.name=t -c user.email=t@example.invalid -c commit.gpgsign=false -c core.autocrlf=false "$@"; }
g init -q
g add -A
g commit -q -m base
base="$(g rev-parse HEAD)"

reset() { g reset -q --hard "$base"; g clean -qfdx; }
run() { # run <args...>: sets rc, writes $tmp/out (stdout+stderr)
  rc=0
  bash "$scr/scripts/new-service.sh" "$@" >"$tmp/out" 2>&1 || rc=$?
}
changed() { g status --porcelain --untracked-files=all | sed 's/^...//' | sort; }
count() { # count <fixed-string> <file>: number of lines containing it
  local n
  n="$(grep -cF -- "$1" "$scr/$2" || true)"
  printf '%s' "${n:-0}"
}
once() { # once <case> <fixed-string> <file>
  local n
  n="$(count "$2" "$3")"
  [ "$n" = 1 ] || fail "$1" "'$2' appears $n times in $3 (want 1)"
}
markers_intact() { # every marker still present exactly once per file
  local f m
  while IFS= read -r f; do
    for m in $(grep -oE 'new-service: [a-z0-9-]+' "$scr/$f" | sort | uniq -d); do
      fail "$1" "marker '$m' duplicated in $f"
    done
  done < <(cd "$scr" && grep -rlE 'new-service: [a-z0-9-]+' --exclude-dir=templates --exclude-dir=docs --exclude-dir=.git --exclude=new-service.sh --exclude=new-service.test.sh . | sed 's|^\./||')
}
no_tokens() { # no leftover __TOKEN__ in changed or new files
  local f bad=""
  while IFS= read -r f; do
    if grep -qE "$token_re" "$scr/$f"; then bad="$bad $f"; fi
  done < <(changed)
  [ -z "$bad" ] || fail "$1" "leftover tokens in:$bad"
}
job_block() { # job_block <job>: the job's lines in the scratch ci.yml, up to the next blank line
  awk -v h="  $1:" '$0 == h {p=1} p && /^$/ {exit} p' "$scr/.github/workflows/ci.yml"
}
untouched() { # untouched <case>: the run left nothing behind
  local c
  c="$(changed)"
  [ -z "$c" ] || fail "$1" "tree changed after a refused run: $c"
}

java_renders_and_registers() {
  local c=${FUNCNAME[0]} f m
  reset
  run --lang java --name demo-java --port 8901
  [ "$rc" -eq 0 ] || fail "$c" "rc=$rc: $(cat "$tmp/out")"
  for f in services/demo-java/pom.xml services/demo-java/Dockerfile \
    services/demo-java/src/main/java/dev/platform/demojava/DemoJavaApplication.java \
    services/demo-java/src/main/java/dev/platform/demojava/adapter/config/DemoJavaConfiguration.java \
    services/demo-java/src/test/java/dev/platform/demojava/HealthIT.java \
    services/demo-java/src/main/resources/application.yml \
    deploy/k8s/base/demo-java/deployment.yaml deploy/k8s/base/demo-java/service.yaml \
    deploy/k8s/base/demo-java/kustomization.yaml contracts/openapi/demo-java.yaml; do
    [ -f "$scr/$f" ] || fail "$c" "missing $f"
  done
  [ ! -e "$scr/deploy/k8s/base/demo-java/deployment-rust.yaml" ] || fail "$c" "rust deployment rendered for java"
  grep -q 'runAsUser: 10001' "$scr/deploy/k8s/base/demo-java/deployment.yaml" || fail "$c" "not the java deployment"
  grep -qx 'package dev.platform.demojava;' "$scr/services/demo-java/src/main/java/dev/platform/demojava/DemoJavaApplication.java" \
    || fail "$c" "package not rendered"
  once "$c" 'port: ${PORT:8901}' services/demo-java/src/main/resources/application.yml
  no_tokens "$c"
  once "$c" '<module>services/demo-java</module>' pom.xml
  once "$c" '  demo-java:' .github/workflows/ci.yml
  once "$c" 'demo_java: ${{ steps.filter.outputs.demo_java }}' .github/workflows/ci.yml
  once "$c" "            demo_java:" .github/workflows/ci.yml
  once "$c" "'services/demo-java/**'" .github/workflows/ci.yml
  job_block demo-java >"$tmp/job"
  for f in '    uses: ./.github/workflows/_java-service.yml' '      module: services/demo-java'; do
    grep -qxF "$f" "$tmp/job" || fail "$c" "ci job has no '$f': $(cat "$tmp/job")"
  done
  grep -qx '      - demo-java' "$scr/.github/workflows/ci.yml" || fail "$c" "not in ci-gate needs"
  once "$c" 'tags: demo-java:e2e' .github/workflows/_k8s-e2e.yml
  once "$c" ' payment-svc web demo-java; do # new-service: e2e-load' .github/workflows/_k8s-e2e.yml
  once "$c" 'payment-svc web demo-java # new-service: cd-services' .github/workflows/cd.yml
  once "$c" 'payment-svc web demo-java) # new-service: cd-bump-services' scripts/cd-bump.sh
  once "$c" 'directory: /services/demo-java' .github/dependabot.yml
  once "$c" '  demo-java:' deploy/local/docker-compose.yml
  once "$c" '"127.0.0.1:${DEMO_JAVA_PORT:-8901}:8901"' deploy/local/docker-compose.yml
  once "$c" '  - ../../base/demo-java' deploy/k8s/overlays/e2e/kustomization.yaml
  once "$c" "{name: $owner/demo-java, newName: demo-java, newTag: e2e}" deploy/k8s/overlays/e2e/kustomization.yaml
  once "$c" '  - ../../../base/demo-java' deploy/k8s/overlays/prod/apps/kustomization.yaml
  once "$c" "{name: $owner/demo-java, newTag: main}" deploy/k8s/overlays/prod/apps/kustomization.yaml
  for f in order-svc reading-tuvi identity-svc payment-svc demo-java; do
    once "$c" 'COPY services/demo-java/pom.xml services/demo-java/' "services/$f/Dockerfile"
  done
  for m in libs/java/platform-starter services/order-svc services/reading-tuvi services/identity-svc services/payment-svc; do
    once "$c" "COPY $m/pom.xml $m/" services/demo-java/Dockerfile
  done
  ! grep -q demo-java "$scr/Cargo.toml" "$scr/Cargo.lock" || fail "$c" "java registered in Cargo.toml/Cargo.lock"
  markers_intact "$c"
  # Only the expected files changed.
  local unexpected
  unexpected="$(changed | grep -vE '^(services/demo-java/|deploy/k8s/base/demo-java/|contracts/openapi/demo-java\.yaml$|pom\.xml$|\.github/workflows/(ci|cd|_k8s-e2e)\.yml$|\.github/dependabot\.yml$|scripts/cd-bump\.sh$|deploy/local/docker-compose\.yml$|deploy/k8s/overlays/(e2e|prod/apps)/kustomization\.yaml$|services/(order-svc|reading-tuvi|identity-svc|payment-svc)/Dockerfile$)' || true)"
  [ -z "$unexpected" ] || fail "$c" "unexpected changes: $unexpected"
  grep -q 'demo-java' "$tmp/out" || fail "$c" "no summary printed"
  echo "ok $c"
}

rust_renders_and_registers() {
  local c=${FUNCNAME[0]} f
  reset
  run --lang rust --name demo-rust --port 8902
  [ "$rc" -eq 0 ] || fail "$c" "rc=$rc: $(cat "$tmp/out")"
  for f in services/demo-rust/Cargo.toml services/demo-rust/Dockerfile services/demo-rust/src/main.rs \
    services/demo-rust/src/domain/info.rs services/demo-rust/tests/architecture.rs services/demo-rust/tests/contract.rs \
    deploy/k8s/base/demo-rust/deployment.yaml contracts/openapi/demo-rust.yaml; do
    [ -f "$scr/$f" ] || fail "$c" "missing $f"
  done
  grep -q 'runAsUser: 65532' "$scr/deploy/k8s/base/demo-rust/deployment.yaml" || fail "$c" "not the rust deployment"
  grep -q '^use demo_rust::config' "$scr/services/demo-rust/src/main.rs" || fail "$c" "crate not rendered"
  once "$c" 'pub const DEFAULT_PORT: u16 = 8902;' services/demo-rust/src/config.rs
  no_tokens "$c"
  once "$c" '"services/demo-rust",' Cargo.toml
  once "$c" 'name = "demo-rust"' Cargo.lock
  # The lock entry lists the crate's dependencies (cargo --locked must accept it; checked in CI job scaffold).
  awk '/^name = "demo-rust"$/{p=1} p&&/^\]$/{exit} p' "$scr/Cargo.lock" >"$tmp/lock-entry"
  for f in '"platform-core",' '"axum",' '"tokio",' '"yaml_serde",'; do
    grep -qxF " $f" "$tmp/lock-entry" || fail "$c" "lock entry has no $f: $(cat "$tmp/lock-entry")"
  done
  once "$c" '  demo-rust:' .github/workflows/ci.yml
  job_block demo-rust >"$tmp/job"
  for f in '    uses: ./.github/workflows/_rust-service.yml' '      package: demo-rust' \
    '      dockerfile: services/demo-rust/Dockerfile'; do
    grep -qxF "$f" "$tmp/job" || fail "$c" "ci job has no '$f': $(cat "$tmp/job")"
  done
  grep -qx '      - demo-rust' "$scr/.github/workflows/ci.yml" || fail "$c" "not in ci-gate needs"
  once "$c" 'directory: /services/demo-rust' .github/dependabot.yml
  once "$c" '"127.0.0.1:${DEMO_RUST_PORT:-8902}:8902"' deploy/local/docker-compose.yml
  once "$c" "{name: $owner/demo-rust, newTag: main}" deploy/k8s/overlays/prod/apps/kustomization.yaml
  ! grep -q demo-rust "$scr/pom.xml" || fail "$c" "rust registered in pom.xml"
  ! grep -q demo-rust "$scr/services/order-svc/Dockerfile" || fail "$c" "rust pom copied into a java Dockerfile"
  markers_intact "$c"
  echo "ok $c"
}

both_languages_coexist_and_ci_stays_valid() {
  local c=${FUNCNAME[0]}
  reset
  run --lang java --name demo-java --port 8901
  [ "$rc" -eq 0 ] || fail "$c" "java rc=$rc: $(cat "$tmp/out")"
  run --lang rust --name demo-rust --port 8902
  [ "$rc" -eq 0 ] || fail "$c" "rust rc=$rc: $(cat "$tmp/out")"
  no_tokens "$c"
  markers_intact "$c"
  if command -v actionlint >/dev/null 2>&1; then
    (cd "$scr" && actionlint) >"$tmp/lint" 2>&1 || fail "$c" "actionlint: $(cat "$tmp/lint")"
  else
    echo "  (actionlint not installed: skipped the workflow lint)"
  fi
  echo "ok $c"
}

second_run_is_refused_without_changes() {
  local c=${FUNCNAME[0]}
  reset
  run --lang rust --name demo-rust --port 8902
  [ "$rc" -eq 0 ] || fail "$c" "first run rc=$rc: $(cat "$tmp/out")"
  g add -A; g commit -q -m rendered
  run --lang rust --name demo-rust --port 8903
  [ "$rc" -ne 0 ] || fail "$c" "second run succeeded"
  grep -qi 'already' "$tmp/out" || fail "$c" "no 'already' in: $(cat "$tmp/out")"
  untouched "$c"
  run --lang java --name demo-other --port 8902
  [ "$rc" -ne 0 ] || fail "$c" "used port 8902 accepted"
  grep -q '8902' "$tmp/out" || fail "$c" "port not named in: $(cat "$tmp/out")"
  untouched "$c"
  echo "ok $c"
}

bad_input_is_refused_without_changes() {
  local c=${FUNCNAME[0]} args
  reset
  while IFS= read -r args; do
    # shellcheck disable=SC2086 # one word per argument, intentional
    run $args
    [ "$rc" -ne 0 ] || fail "$c" "accepted: $args"
    untouched "$c"
  done <<'EOF'
--lang java --name Bad_Name --port 8901
--lang java --name ab --port 8901
--lang java --name abcdefghij-abcdefghij-abcdefghij --port 8901
--lang java --name demo--svc --port 8901
--lang java --name demo-svc- --port 8901
--lang java --name 1demo --port 8901
--lang java --name web --port 8901
--lang java --name order-svc --port 8901
--lang java --name postgres --port 8901
--lang java --name order --port 8901
--lang java --name scaffold --port 8901
--lang java --name enum --port 8901
--lang rust --name match --port 8901
--lang java --name demo-svc --port 8085
--lang java --name demo-svc --port 9000
--lang java --name demo-svc --port 89o1
--lang go --name demo-svc --port 8901
--name demo-svc --port 8901
--lang java --port 8901
--lang java --name demo-svc
--lang java --name demo-svc --port 8901 --extra
EOF
  echo "ok $c"
}

missing_marker_leaves_tree_untouched() {
  local c=${FUNCNAME[0]}
  reset
  # The last registration target loses its marker: everything before it must not be half-edited.
  sed -i '/new-service: dependabot/d' "$scr/.github/dependabot.yml"
  g add -A; g commit -q -m no-marker
  run --lang java --name demo-java --port 8901
  [ "$rc" -ne 0 ] || fail "$c" "succeeded without a marker"
  grep -q 'dependabot' "$tmp/out" || fail "$c" "marker/file not named in: $(cat "$tmp/out")"
  untouched "$c"
  echo "ok $c"
}

crlf_files_keep_crlf() {
  local c=${FUNCNAME[0]} f lf n
  reset
  for f in pom.xml .github/workflows/ci.yml deploy/local/docker-compose.yml scripts/cd-bump.sh Cargo.toml; do
    sed -i 's/\r*$/\r/' "$scr/$f"
  done
  # .gitattributes (eol=lf) normalizes these on commit; the CRLF stays in the working files under test.
  g add -A 2>/dev/null; g commit -q --allow-empty -m crlf
  run --lang java --name demo-java --port 8901
  [ "$rc" -eq 0 ] || fail "$c" "java rc=$rc: $(cat "$tmp/out")"
  run --lang rust --name demo-rust --port 8902
  [ "$rc" -eq 0 ] || fail "$c" "rust rc=$rc: $(cat "$tmp/out")"
  # awk in binary mode (BINMODE=3; Git Bash gawk otherwise strips CR on read), not grep (same problem).
  for f in pom.xml .github/workflows/ci.yml deploy/local/docker-compose.yml scripts/cd-bump.sh Cargo.toml; do
    lf="$(awk -v BINMODE=3 '!/\r$/ { n++ } END { print n + 0 }' "$scr/$f")"
    [ "$lf" = 0 ] || fail "$c" "$f: $lf lines lost their CRLF after insertion"
  done
  for f in 'pom.xml|<module>services/demo-java</module>' \
    'scripts/cd-bump.sh|payment-svc web demo-java demo-rust) # new-service: cd-bump-services' \
    'Cargo.toml|  "services/demo-rust",' '.github/workflows/ci.yml|      - demo-rust'; do
    n="$(awk -v BINMODE=3 -v s="${f#*|}" 'index($0, s) == 1 || index($0, " " s) { n++ } END { print n + 0 }' "$scr/${f%%|*}")"
    [ "$n" = 1 ] || fail "$c" "'${f#*|}' appears $n times in ${f%%|*} (want 1)"
  done
  echo "ok $c"
}

java_renders_and_registers
rust_renders_and_registers
both_languages_coexist_and_ci_stays_valid
second_run_is_refused_without_changes
bad_input_is_refused_without_changes
missing_marker_leaves_tree_untouched
crlf_files_keep_crlf
echo "new-service tests: ok"

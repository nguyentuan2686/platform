#!/usr/bin/env bash
# shellcheck disable=SC2016 # literal ${...} in expected file contents, intentional
# Tests for scripts/new-service.sh, offline: renders a Java and a Rust service into a scratch git copy of the working
# tree (tracked + untracked, not ignored) and checks the files, every registration (exactly once), that the prod path
# (prod overlay, cd.yml, cd-bump.sh) is only added by `--prod` (after an anonymous pull check, stubbed here with a
# fake `docker`), the refusals (second run, bad name/port/lang, collisions, used port, missing marker), the rollback
# of a failed write, CRLF files, and two static template checks (import order, version pins).
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

# Fake `docker` for the --prod pull check: logs its arguments and DOCKER_CONFIG, exits $FAKE_DOCKER_RC.
fake="$tmp/fakebin"
mkdir -p "$fake"
cat >"$fake/docker" <<EOF
#!/usr/bin/env bash
printf '%s|%s\n' "\$*" "\${DOCKER_CONFIG:-}" >>"$tmp/docker.log"
exit "\${FAKE_DOCKER_RC:-1}"
EOF
chmod +x "$fake/docker"

reset() { g reset -q --hard "$base"; g clean -qfdx; }
run() { # run <args...>: sets rc, writes $tmp/out (stdout+stderr); the fake docker is first on PATH
  rc=0
  PATH="$fake:$PATH" bash "$scr/scripts/new-service.sh" "$@" >"$tmp/out" 2>&1 || rc=$?
}
commit_all() { g add -A 2>/dev/null; g commit -q --allow-empty -m "$1"; }
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
never() { # never <case> <fixed-string> <file>
  local n
  n="$(count "$2" "$3")"
  [ "$n" = 0 ] || fail "$1" "'$2' appears $n times in $3 (want 0)"
}
markers_intact() { # every marker still present exactly once per file
  local f m
  while IFS= read -r f; do
    for m in $(grep -oE 'new-service: [a-z0-9-]+' "$scr/$f" | sort | uniq -d); do
      fail "$1" "marker '$m' duplicated in $f"
    done
  done < <(cd "$scr" && grep -rlE 'new-service: [a-z0-9-]+' --exclude-dir=templates --exclude-dir=docs --exclude-dir=.git --exclude-dir=.claude --exclude=new-service.sh --exclude=new-service.test.sh --exclude=CLAUDE.md . | sed 's|^\./||')
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
only_changed() { # only_changed <case> <ERE of allowed paths>
  local unexpected
  unexpected="$(changed | grep -vE "$2" || true)"
  [ -z "$unexpected" ] || fail "$1" "unexpected changes: $unexpected"
}
not_in_prod() { # not_in_prod <case> <name>: the default run leaves the prod path alone
  never "$1" "base/$2" deploy/k8s/overlays/prod/apps/kustomization.yaml
  never "$1" "$owner/$2," deploy/k8s/overlays/prod/apps/kustomization.yaml
  never "$1" " $2 " .github/workflows/cd.yml
  never "$1" " $2)" scripts/cd-bump.sh
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
  once "$c" 'enableServiceLinks: false' deploy/k8s/base/demo-java/deployment.yaml
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
  once "$c" 'directory: /services/demo-java' .github/dependabot.yml
  once "$c" '  demo-java:' deploy/local/docker-compose.yml
  once "$c" '"127.0.0.1:${DEMO_JAVA_PORT:-8901}:8901"' deploy/local/docker-compose.yml
  once "$c" '  - ../../base/demo-java' deploy/k8s/overlays/e2e/kustomization.yaml
  once "$c" "{name: $owner/demo-java, newName: demo-java, newTag: e2e}" deploy/k8s/overlays/e2e/kustomization.yaml
  not_in_prod "$c" demo-java
  for f in order-svc reading-tuvi identity-svc payment-svc demo-java; do
    once "$c" 'COPY services/demo-java/pom.xml services/demo-java/' "services/$f/Dockerfile"
  done
  for m in libs/java/platform-starter services/order-svc services/reading-tuvi services/identity-svc services/payment-svc; do
    once "$c" "COPY $m/pom.xml $m/" services/demo-java/Dockerfile
  done
  ! grep -q demo-java "$scr/Cargo.toml" "$scr/Cargo.lock" || fail "$c" "java registered in Cargo.toml/Cargo.lock"
  markers_intact "$c"
  only_changed "$c" '^(services/demo-java/|deploy/k8s/base/demo-java/|contracts/openapi/demo-java\.yaml$|pom\.xml$|\.github/workflows/(ci|_k8s-e2e)\.yml$|\.github/dependabot\.yml$|deploy/local/docker-compose\.yml$|deploy/k8s/overlays/e2e/kustomization\.yaml$|services/(order-svc|reading-tuvi|identity-svc|payment-svc)/Dockerfile$)'
  grep -q -- '--prod demo-java' "$tmp/out" || fail "$c" "checklist does not name the --prod follow-up: $(cat "$tmp/out")"
  echo "ok $c"
}

rust_renders_and_registers() {
  local c=${FUNCNAME[0]} f
  reset
  run --lang rust --name demo-rs --port 8902
  [ "$rc" -eq 0 ] || fail "$c" "rc=$rc: $(cat "$tmp/out")"
  for f in services/demo-rs/Cargo.toml services/demo-rs/Dockerfile services/demo-rs/src/main.rs \
    services/demo-rs/src/domain/info.rs services/demo-rs/tests/architecture.rs services/demo-rs/tests/contract.rs \
    deploy/k8s/base/demo-rs/deployment.yaml contracts/openapi/demo-rs.yaml; do
    [ -f "$scr/$f" ] || fail "$c" "missing $f"
  done
  grep -q 'runAsUser: 65532' "$scr/deploy/k8s/base/demo-rs/deployment.yaml" || fail "$c" "not the rust deployment"
  once "$c" 'enableServiceLinks: false' deploy/k8s/base/demo-rs/deployment.yaml
  grep -q '^use demo_rs::config' "$scr/services/demo-rs/src/main.rs" || fail "$c" "crate not rendered"
  once "$c" 'pub const DEFAULT_PORT: u16 = 8902;' services/demo-rs/src/config.rs
  no_tokens "$c"
  once "$c" '"services/demo-rs",' Cargo.toml
  once "$c" 'name = "demo-rs"' Cargo.lock
  # The lock entry lists the crate's dependencies (cargo --locked must accept it; checked in CI job scaffold).
  awk '/^name = "demo-rs"$/{p=1} p&&/^\]$/{exit} p' "$scr/Cargo.lock" >"$tmp/lock-entry"
  for f in '"platform-core",' '"axum",' '"tokio",' '"yaml_serde",'; do
    grep -qxF " $f" "$tmp/lock-entry" || fail "$c" "lock entry has no $f: $(cat "$tmp/lock-entry")"
  done
  once "$c" '  demo-rs:' .github/workflows/ci.yml
  job_block demo-rs >"$tmp/job"
  for f in '    uses: ./.github/workflows/_rust-service.yml' '      package: demo-rs' \
    '      dockerfile: services/demo-rs/Dockerfile'; do
    grep -qxF "$f" "$tmp/job" || fail "$c" "ci job has no '$f': $(cat "$tmp/job")"
  done
  grep -qx '      - demo-rs' "$scr/.github/workflows/ci.yml" || fail "$c" "not in ci-gate needs"
  once "$c" 'directory: /services/demo-rs' .github/dependabot.yml
  once "$c" '"127.0.0.1:${DEMO_RS_PORT:-8902}:8902"' deploy/local/docker-compose.yml
  not_in_prod "$c" demo-rs
  ! grep -q demo-rs "$scr/pom.xml" || fail "$c" "rust registered in pom.xml"
  ! grep -q demo-rs "$scr/services/order-svc/Dockerfile" || fail "$c" "rust pom copied into a java Dockerfile"
  markers_intact "$c"
  only_changed "$c" '^(services/demo-rs/|deploy/k8s/base/demo-rs/|contracts/openapi/demo-rs\.yaml$|Cargo\.(toml|lock)$|\.github/workflows/(ci|_k8s-e2e)\.yml$|\.github/dependabot\.yml$|deploy/local/docker-compose\.yml$|deploy/k8s/overlays/e2e/kustomization\.yaml$)'
  echo "ok $c"
}

lock_entry_sorting_last_is_appended() {
  local c=${FUNCNAME[0]} last
  reset
  run --lang rust --name zzzz-rs --port 8903
  [ "$rc" -eq 0 ] || fail "$c" "rc=$rc: $(cat "$tmp/out")"
  last="$(grep '^name = ' "$scr/Cargo.lock" | tail -n 1)"
  [ "$last" = 'name = "zzzz-rs"' ] || fail "$c" "last package is $last"
  # Exactly one blank line between the previous entry and the new one, and the file ends with the entry's `]`.
  awk '/^\[\[package\]\]$/ { if (prev != "") bad = 1 } { prev = $0 } END { if (prev != "]") bad = 1; exit bad }' \
    "$scr/Cargo.lock" || fail "$c" "malformed tail: $(tail -n 25 "$scr/Cargo.lock")"
  echo "ok $c"
}

prod_is_a_separate_checked_step() {
  local c=${FUNCNAME[0]}
  reset
  run --lang java --name demo-java --port 8901
  [ "$rc" -eq 0 ] || fail "$c" "render rc=$rc: $(cat "$tmp/out")"
  commit_all rendered
  # The image is not anonymously pullable (private package or not published yet): refused, nothing written.
  rm -f "$tmp/docker.log"
  FAKE_DOCKER_RC=1 run --prod demo-java
  [ "$rc" -ne 0 ] || fail "$c" "--prod succeeded without a pullable image"
  grep -qi 'public' "$tmp/out" || fail "$c" "refusal does not say why: $(cat "$tmp/out")"
  untouched "$c"
  grep -q "^manifest inspect $owner/demo-java:main|." "$tmp/docker.log" || fail "$c" "no anonymous manifest check: $(cat "$tmp/docker.log")"
  ! grep -qF "|$HOME/.docker" "$tmp/docker.log" || fail "$c" "pull check used the user's docker credentials"
  # Pullable: exactly the prod path is added.
  FAKE_DOCKER_RC=0 run --prod demo-java
  [ "$rc" -eq 0 ] || fail "$c" "--prod rc=$rc: $(cat "$tmp/out")"
  once "$c" '  - ../../../base/demo-java' deploy/k8s/overlays/prod/apps/kustomization.yaml
  once "$c" "{name: $owner/demo-java, newTag: main}" deploy/k8s/overlays/prod/apps/kustomization.yaml
  once "$c" 'payment-svc web demo-java # new-service: cd-services' .github/workflows/cd.yml
  once "$c" 'payment-svc web demo-java) # new-service: cd-bump-services' scripts/cd-bump.sh
  only_changed "$c" '^(deploy/k8s/overlays/prod/apps/kustomization\.yaml|\.github/workflows/cd\.yml|scripts/cd-bump\.sh)$'
  markers_intact "$c"
  commit_all prod
  FAKE_DOCKER_RC=0 run --prod demo-java
  [ "$rc" -ne 0 ] || fail "$c" "second --prod succeeded"
  grep -qi 'already' "$tmp/out" || fail "$c" "no 'already' in: $(cat "$tmp/out")"
  untouched "$c"
  run --prod nope-svc --skip-pull-check
  [ "$rc" -ne 0 ] || fail "$c" "--prod of a service that was never scaffolded succeeded"
  untouched "$c"
  # --skip-pull-check never calls docker.
  run --lang rust --name demo-rs --port 8902
  [ "$rc" -eq 0 ] || fail "$c" "rust render rc=$rc: $(cat "$tmp/out")"
  commit_all rust
  rm -f "$tmp/docker.log"
  FAKE_DOCKER_RC=1 run --prod demo-rs --skip-pull-check
  [ "$rc" -eq 0 ] || fail "$c" "--skip-pull-check rc=$rc: $(cat "$tmp/out")"
  [ ! -s "$tmp/docker.log" ] || fail "$c" "docker called despite --skip-pull-check"
  once "$c" 'payment-svc web demo-java demo-rs # new-service: cd-services' .github/workflows/cd.yml
  echo "ok $c"
}

both_languages_coexist_and_ci_stays_valid() {
  local c=${FUNCNAME[0]}
  reset
  run --lang java --name demo-java --port 8901
  [ "$rc" -eq 0 ] || fail "$c" "java rc=$rc: $(cat "$tmp/out")"
  run --lang rust --name demo-rs --port 8902
  [ "$rc" -eq 0 ] || fail "$c" "rust rc=$rc: $(cat "$tmp/out")"
  run --prod demo-java --skip-pull-check
  [ "$rc" -eq 0 ] || fail "$c" "prod java rc=$rc: $(cat "$tmp/out")"
  run --prod demo-rs --skip-pull-check
  [ "$rc" -eq 0 ] || fail "$c" "prod rust rc=$rc: $(cat "$tmp/out")"
  no_tokens "$c"
  markers_intact "$c"
  if command -v actionlint >/dev/null 2>&1; then
    (cd "$scr" && actionlint) >"$tmp/lint" 2>&1 || fail "$c" "actionlint: $(cat "$tmp/lint")"
  else
    echo "  (actionlint not installed: skipped the workflow lint)"
  fi
  echo "ok $c"
}

ci_scaffold_names_need_the_ci_flag() {
  local c=${FUNCNAME[0]}
  reset
  run --lang java --name zz-java --port 8998
  [ "$rc" -ne 0 ] || fail "$c" "zz-java accepted without NEW_SERVICE_CI_SCAFFOLD"
  untouched "$c"
  NEW_SERVICE_CI_SCAFFOLD=1 run --lang java --name zz-java --port 8998
  [ "$rc" -eq 0 ] || fail "$c" "CI scaffold render rc=$rc: $(cat "$tmp/out")"
  echo "ok $c"
}

second_run_is_refused_without_changes() {
  local c=${FUNCNAME[0]}
  reset
  run --lang rust --name demo-rs --port 8902
  [ "$rc" -eq 0 ] || fail "$c" "first run rc=$rc: $(cat "$tmp/out")"
  commit_all rendered
  run --lang rust --name demo-rs --port 8903
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
--lang java --name 1demo-svc --port 8901
--lang java --name web --port 8901
--lang java --name order --port 8901
--lang java --name server --port 8901
--lang rust --name build --port 8901
--lang java --name con --port 8901
--lang java --name order-svc --port 8901
--lang java --name star-ter --port 8901
--lang java --name orde-r --port 8901
--lang java --name en-um --port 8901
--lang rust --name serde-json --port 8901
--lang rust --name platform-core --port 8901
--lang java --name zz-java --port 8901
--lang rust --name demo-rust --port 8901
--lang java --name demo-svc --port 8998
--lang rust --name demo-svc --port 8999
--lang java --name demo-svc --port 8085
--lang java --name demo-svc --port 9000
--lang java --name demo-svc --port 08901
--lang java --name demo-svc --port +8901
--lang java --name demo-svc --port 0x22F5
--lang java --name demo-svc --port 89o1
--lang go --name demo-svc --port 8901
--name demo-svc --port 8901
--lang java --port 8901
--lang java --name demo-svc
--lang java --name demo-svc --port 8901 --extra
--prod
--prod demo-svc --lang java
--prod Bad_Name --skip-pull-check
--lang java --name demo-svc --port 8901 --skip-pull-check
EOF
  echo "ok $c"
}

missing_marker_leaves_tree_untouched() {
  local c=${FUNCNAME[0]}
  reset
  # The last registration target loses its marker: everything before it must not be half-edited.
  sed -i '/new-service: dependabot/d' "$scr/.github/dependabot.yml"
  commit_all no-marker
  run --lang java --name demo-java --port 8901
  [ "$rc" -ne 0 ] || fail "$c" "succeeded without a marker"
  grep -q 'dependabot' "$tmp/out" || fail "$c" "marker/file not named in: $(cat "$tmp/out")"
  untouched "$c"
  # A Java Dockerfile that runs Maven but has no java-poms marker would miss the new module's pom: refused.
  reset
  sed -i '/new-service: java-poms/d' "$scr/services/payment-svc/Dockerfile"
  commit_all no-poms-marker
  run --lang java --name demo-java --port 8901
  [ "$rc" -ne 0 ] || fail "$c" "succeeded with a Java Dockerfile without the java-poms marker"
  grep -q 'services/payment-svc/Dockerfile' "$tmp/out" || fail "$c" "Dockerfile not named in: $(cat "$tmp/out")"
  untouched "$c"
  echo "ok $c"
}

failed_write_is_rolled_back() {
  local c=${FUNCNAME[0]}
  reset
  # Test hook: the script fails after writing the new files and the first edited file.
  NEW_SERVICE_TEST_FAIL_WRITE=1 run --lang java --name demo-java --port 8901
  [ "$rc" -ne 0 ] || fail "$c" "the injected write failure did not fail the run"
  grep -qi 'rolled back' "$tmp/out" || fail "$c" "no rollback message: $(cat "$tmp/out")"
  untouched "$c"
  echo "ok $c"
}

crlf_files_keep_crlf() {
  local c=${FUNCNAME[0]} f lf n
  local crlf=(pom.xml .github/workflows/ci.yml .github/workflows/cd.yml .github/workflows/_k8s-e2e.yml
    .github/dependabot.yml deploy/local/docker-compose.yml deploy/k8s/overlays/e2e/kustomization.yaml
    deploy/k8s/overlays/prod/apps/kustomization.yaml scripts/cd-bump.sh Cargo.toml Cargo.lock
    services/order-svc/Dockerfile services/payment-svc/Dockerfile)
  reset
  for f in "${crlf[@]}"; do sed -i 's/\r*$/\r/' "$scr/$f"; done
  # .gitattributes (eol=lf) normalizes these on commit; the CRLF stays in the working files under test.
  commit_all crlf
  run --lang java --name demo-java --port 8901
  [ "$rc" -eq 0 ] || fail "$c" "java rc=$rc: $(cat "$tmp/out")"
  run --lang rust --name demo-rs --port 8902
  [ "$rc" -eq 0 ] || fail "$c" "rust rc=$rc: $(cat "$tmp/out")"
  run --prod demo-java --skip-pull-check
  [ "$rc" -eq 0 ] || fail "$c" "prod java rc=$rc: $(cat "$tmp/out")"
  run --prod demo-rs --skip-pull-check
  [ "$rc" -eq 0 ] || fail "$c" "prod rust rc=$rc: $(cat "$tmp/out")"
  # awk in binary mode (BINMODE=3; Git Bash gawk otherwise strips CR on read), not grep (same problem).
  for f in "${crlf[@]}"; do
    lf="$(awk -v BINMODE=3 '!/\r$/ { n++ } END { print n + 0 }' "$scr/$f")"
    [ "$lf" = 0 ] || fail "$c" "$f: $lf lines lost their CRLF after insertion"
  done
  for f in 'pom.xml|<module>services/demo-java</module>' \
    'scripts/cd-bump.sh|payment-svc web demo-java demo-rs) # new-service: cd-bump-services' \
    'Cargo.toml|  "services/demo-rs",' '.github/workflows/ci.yml|      - demo-rs' 'Cargo.lock|name = "demo-rs"' \
    'services/order-svc/Dockerfile|COPY services/demo-java/pom.xml services/demo-java/'; do
    n="$(awk -v BINMODE=3 -v s="${f#*|}" 'index($0, s) == 1 || index($0, " " s) { n++ } END { print n + 0 }' "$scr/${f%%|*}")"
    [ "$n" = 1 ] || fail "$c" "'${f#*|}' appears $n times in ${f%%|*} (want 1)"
  done
  echo "ok $c"
}

# Static template checks (no rendering).
java_template_imports_do_not_depend_on_the_name() {
  local c=${FUNCNAME[0]} bad
  # google-java-format sorts imports in one block: an import of another dev.platform package next to the service's
  # own (dev.platform.__JAVA_PKG__...) would move above or below it depending on the name. Fully qualify those.
  bad="$(grep -rnE '^import (static )?dev\.platform\.' "$root/templates/java-service" | grep -v 'dev\.platform\.__JAVA_PKG__' || true)"
  [ -z "$bad" ] || fail "$c" "imports of other dev.platform packages in the Java template: $bad"
  echo "ok $c"
}

template_pins_match_the_reference_services() {
  local c=${FUNCNAME[0]} pair tpl ref a b channel
  pins() { grep -E '^(FROM |ARG [A-Z0-9_]+=|ENV RUSTUP_TOOLCHAIN=)' "$1" || true; }
  for pair in 'java-service|order-svc' 'rust-service|chart-svc'; do
    tpl="$root/templates/${pair%%|*}/Dockerfile" ref="$root/services/${pair#*|}/Dockerfile"
    a="$(pins "$tpl")" b="$(pins "$ref")"
    [ "$(printf '%s\n' "$a" | wc -l)" -ge 3 ] || fail "$c" "too few pins found in $tpl: $a"
    [ "$a" = "$b" ] || fail "$c" "version pins differ: templates/${pair%%|*}/Dockerfile vs services/${pair#*|}/Dockerfile:
$(diff <(printf '%s\n' "$a") <(printf '%s\n' "$b") || true)"
  done
  channel="$(sed -nE 's/^channel *= *"([^"]+)".*/\1/p' "$root/rust-toolchain.toml")"
  if ! grep -qx "ENV RUSTUP_TOOLCHAIN=$channel" "$root/templates/rust-service/Dockerfile" \
    || ! grep -q "^FROM rust:$channel-" "$root/templates/rust-service/Dockerfile"; then
    fail "$c" "templates/rust-service/Dockerfile does not use rust-toolchain.toml channel $channel"
  fi
  echo "ok $c"
}

java_template_imports_do_not_depend_on_the_name
template_pins_match_the_reference_services
java_renders_and_registers
rust_renders_and_registers
lock_entry_sorting_last_is_appended
prod_is_a_separate_checked_step
both_languages_coexist_and_ci_stays_valid
ci_scaffold_names_need_the_ci_flag
second_run_is_refused_without_changes
bad_input_is_refused_without_changes
missing_marker_leaves_tree_untouched
failed_write_is_rolled_back
crlf_files_keep_crlf
echo "new-service tests: ok"

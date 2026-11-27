#!/usr/bin/env bash
# Scaffold a new stateless HTTP service (Java or Rust) and register it everywhere the platform lists services,
# except the prod path, which is a separate, later step.
# Usage:
#   scripts/new-service.sh --lang java|rust --name <name> --port <port>
#     <name>: ^[a-z][a-z0-9]*(-[a-z0-9]+)+$ (at least one dash), 3-30 chars; not an existing service, reserved name,
#             Java package (dev.platform.<name without dashes>) or Cargo package/crate.
#     <port>: 8086-8999 (decimal, no leading zero), not used in deploy/local/docker-compose.yml or
#             deploy/k8s/base/*/*.yaml (it is the container port and the local compose host port).
#   scripts/new-service.sh --prod <name> [--skip-pull-check]
#     Adds exactly the prod registration of an already scaffolded service: deploy/k8s/overlays/prod/apps (resource +
#     image `newTag: main`), cd.yml and scripts/cd-bump.sh SERVICES. Run it in a follow-up PR, after the first push
#     to main published the image AND the GHCR package was made public: it first checks that
#     ghcr.io/nguyentuan2686/<name>:main is pullable anonymously (`docker manifest inspect` with an empty
#     DOCKER_CONFIG) and refuses otherwise, since prod has no image pull secret (an unpullable image makes
#     verify-deploy fail and CD revert). --skip-pull-check skips that check (no docker needed).
# Scaffold mode renders templates/{java-service|rust-service} into services/<name>, templates/k8s into
# deploy/k8s/base/<name> and templates/contracts/openapi.yaml into contracts/openapi/<name>.yaml, then inserts the
# snippets of templates/registration at the `new-service: <point>` marker comments of the shared files (plain text
# insertion, no YAML/XML round trip; CRLF files stay CRLF). Rust: also the Cargo.lock entry of the new member, derived
# from the dependency lines the existing members already lock.
# Safe: every check runs first and all edits are staged in a temp dir; the tree is only written when all of them
# passed, so a refused run changes nothing, and a write that fails midway (I/O error, Ctrl-C) is rolled back: the
# edited files are restored from a backup and the new paths removed. A second run for the same name is refused.
# The names zz-java / demo-rust and ports 8998 / 8999 belong to the CI job `scaffold` (NEW_SERVICE_CI_SCAFFOLD=1).
# Exit 2: usage or invalid name/port. Exit 1: refused (already registered, collision, port in use, marker missing,
# image not pullable) or error. Needs bash, sed, awk (and docker for the --prod pull check).
# Test: scripts/test/new-service.test.sh (NEW_SERVICE_TEST_FAIL_WRITE=1 is its hook to fail the write phase).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export LC_ALL=C

IMAGE_PREFIX=ghcr.io/nguyentuan2686
CI=.github/workflows/ci.yml
E2E_WF=.github/workflows/_k8s-e2e.yml
CD_WF=.github/workflows/cd.yml
CD_BUMP=scripts/cd-bump.sh
DEPENDABOT=.github/dependabot.yml
COMPOSE=deploy/local/docker-compose.yml
E2E_K=deploy/k8s/overlays/e2e/kustomization.yaml
PROD_K=deploy/k8s/overlays/prod/apps/kustomization.yaml
TPL=templates
# Names that would clash with a CI job, a compose/k8s infra service or a platform name (single words are already
# excluded by the dash rule, which also rules out Kubernetes service-link env clashes such as server -> SERVER_PORT
# and Cargo/Windows reserved names such as build, deps, con, nul).
RESERVED=" ci-gate k8s-manifests k8s-e2e ops-scripts otel-collector platform-starter platform-core "
CI_SCAFFOLD_NAMES=" zz-java demo-rust "
CI_SCAFFOLD_PORTS=" 8998 8999 "
JAVA_KEYWORDS=" abstract assert boolean break byte case catch char class const continue default double else enum
  extends false final finally float for goto implements import instanceof int interface long native new null package
  private protected public record return sealed short static strictfp super switch synchronized this throw throws
  transient true try var void volatile while yield permits "

usage() {
  echo "new-service: $1" >&2
  echo "usage: $0 --lang java|rust --name <name> --port <8086-8999>" >&2
  echo "       $0 --prod <name> [--skip-pull-check]" >&2
  exit 2
}
refuse() { echo "new-service: refused: $1 (nothing was changed)" >&2; exit 1; }

mode=scaffold lang="" name="" port="" skip_pull=0
while [ $# -gt 0 ]; do
  case "$1" in
    --lang | --name | --port | --prod)
      [ $# -ge 2 ] || usage "$1 needs a value"
      case "$1" in
        --lang) lang="$2" ;;
        --name) name="$2" ;;
        --port) port="$2" ;;
        --prod) mode=prod; name="$2" ;;
      esac
      shift 2
      ;;
    --skip-pull-check) skip_pull=1; shift ;;
    *) usage "unknown argument '$1'" ;;
  esac
done
if [ "$mode" = prod ]; then
  [ -z "$lang" ] && [ -z "$port" ] || usage "--prod takes only a name (and --skip-pull-check)"
else
  [ "$skip_pull" = 0 ] || usage "--skip-pull-check only goes with --prod"
  [ -n "$lang" ] || usage "--lang is required"
  [ -n "$name" ] || usage "--name is required"
  [ -n "$port" ] || usage "--port is required"
  case "$lang" in java | rust) ;; *) usage "--lang must be java or rust, got '$lang'" ;; esac
fi
[[ "$name" =~ ^[a-z][a-z0-9]*(-[a-z0-9]+)+$ ]] \
  || usage "invalid name '$name' (lowercase letters and digits in dash-separated words, at least one dash)"
[ "${#name}" -ge 3 ] && [ "${#name}" -le 30 ] || usage "name '$name' must be 3-30 characters"
if [ "$mode" = scaffold ]; then
  case "$RESERVED" in *[[:space:]]"$name"[[:space:]]*) usage "name '$name' is reserved" ;; esac
  [[ "$port" =~ ^[1-9][0-9]*$ ]] || usage "invalid port '$port' (decimal, no leading zero)"
  [ "$port" -ge 8086 ] && [ "$port" -le 8999 ] || usage "port $port is outside 8086-8999"
  if [ "${NEW_SERVICE_CI_SCAFFOLD:-}" != 1 ]; then
    case "$CI_SCAFFOLD_NAMES" in *" $name "*) usage "name '$name' is reserved for the CI job scaffold" ;; esac
    case "$CI_SCAFFOLD_PORTS" in *" $port "*) usage "port $port is reserved for the CI job scaffold" ;; esac
  fi
fi

# Derived tokens (R2).
pkg="${name//-/}"
crate="${name//-/_}"
env_prefix="$(printf '%s' "$crate" | tr '[:lower:]' '[:upper:]')"
class=""
IFS=- read -r -a parts <<<"$name"
for p in "${parts[@]}"; do class="$class$(printf '%s' "${p:0:1}" | tr '[:lower:]' '[:upper:]')${p:1}"; done

# ---------------------------------------------------------------- checks (nothing written yet)
for f in "$CI" "$E2E_WF" "$CD_WF" "$CD_BUMP" "$DEPENDABOT" "$COMPOSE" "$E2E_K" "$PROD_K" pom.xml Cargo.toml Cargo.lock; do
  [ -f "$f" ] || refuse "missing $f"
done

# already_in FILE ERE: the name is already a key, list entry, path or image there (or collides with one).
already_in() {
  if grep -qE -- "$2" "$1"; then refuse "'$name' is already registered in, or collides with an existing entry of, $1"; fi
}
# marker_ok FILE POINT: the marker `new-service: POINT` is on exactly one line.
marker_re() { printf 'new-service: %s([^a-z0-9-]|$)' "$1"; }
marker_ok() {
  local n
  n="$(grep -cE "$(marker_re "$2")" "$1" || true)"
  [ "$n" = 1 ] || refuse "marker 'new-service: $2' must be on exactly one line of $1 (found ${n:-0})"
}

prod_checks() {
  already_in "$PROD_K" "(base/$name|$IMAGE_PREFIX/$name,)"
  already_in "$CD_WF" "^ *SERVICES:.*[[:space:]]$name([[:space:]]|$)"
  already_in "$CD_BUMP" "^SERVICES=\\(.*[( ]${name}[ )]"
  marker_ok "$CD_WF" cd-services
  marker_ok "$CD_BUMP" cd-bump-services
  marker_ok "$PROD_K" prod-resources
  marker_ok "$PROD_K" prod-images
}

java_dockerfiles=()
if [ "$mode" = prod ]; then
  [ -d "services/$name" ] && [ -d "deploy/k8s/base/$name" ] \
    || refuse "'$name' is not a scaffolded service (no services/$name or deploy/k8s/base/$name)"
  prod_checks
else
  for d in "$TPL/$lang-service" "$TPL/k8s" "$TPL/registration" "$TPL/contracts"; do
    [ -d "$d" ] || refuse "missing template directory $d"
  done
  [ ! -e "services/$name" ] || refuse "services/$name already exists"
  [ ! -e "deploy/k8s/base/$name" ] || refuse "deploy/k8s/base/$name already exists"
  [ ! -e "contracts/openapi/$name.yaml" ] || refuse "contracts/openapi/$name.yaml already exists"
  already_in "$CI" "^ *($name|$crate):([[:space:]]|$)"
  already_in "$CI" "^ *- $name([[:space:]]|$)"
  already_in "$COMPOSE" "^ *$name:([[:space:]]|$)"
  already_in pom.xml "<module>services/$name</module>"
  already_in Cargo.toml "\"services/$name\""
  already_in "$E2E_K" "(base/$name|$IMAGE_PREFIX/$name,)"
  already_in "$E2E_WF" "(services/$name/|[[:space:]]$name:e2e|[[:space:]]${name}[[:space:];])"
  already_in "$DEPENDABOT" "directory: /services/$name([[:space:]]|$)"
  prod_checks # a name already on the prod path is a collision too
  if [ "$lang" = java ]; then
    case "$JAVA_KEYWORDS" in *[[:space:]]"$pkg"[[:space:]]*) usage "'$pkg' is a Java keyword (package dev.platform.$pkg)" ;; esac
    for d in libs/java/*/src/*/java/dev/platform/"$pkg" services/*/src/*/java/dev/platform/"$pkg"; do
      [ ! -d "$d" ] || refuse "Java package dev.platform.$pkg already exists ($d)"
    done
  else
    # A member must not share its name with a locked package or another crate (cargo normalizes - and _).
    if sed -n 's/^name = "\([^"]*\)".*/\1/p' Cargo.lock | tr '_' '-' | grep -qx -- "$name"; then
      refuse "Cargo package or crate '$name' / '$crate' already exists in Cargo.lock"
    fi
  fi
  port_files=("$COMPOSE")
  for f in deploy/k8s/base/*/*.yaml; do [ -f "$f" ] && port_files+=("$f"); done
  for f in "${port_files[@]}"; do
    if grep -qE "(^|[^0-9])$port([^0-9]|$)" "$f"; then refuse "port $port is already used in $f"; fi
  done
  marker_ok "$CI" ci-outputs
  marker_ok "$CI" ci-filters
  marker_ok "$CI" ci-jobs
  marker_ok "$CI" ci-gate-needs
  marker_ok "$E2E_WF" e2e-builds
  marker_ok "$E2E_WF" e2e-load
  marker_ok "$DEPENDABOT" dependabot
  marker_ok "$COMPOSE" compose
  marker_ok "$E2E_K" e2e-resources
  marker_ok "$E2E_K" e2e-images
  if [ "$lang" = java ]; then
    marker_ok pom.xml maven-modules
    for f in services/*/Dockerfile; do
      if grep -qE "$(marker_re java-poms)" "$f"; then
        marker_ok "$f" java-poms
        java_dockerfiles+=("$f")
      elif grep -qE '(^|[[:space:]])mvn[[:space:]]' "$f"; then
        refuse "$f runs Maven but has no 'new-service: java-poms' marker (its image would miss the new module's pom)"
      fi
    done
  else
    marker_ok Cargo.toml cargo-members
  fi
  build_push="$(grep -oE 'docker/build-push-action@[0-9a-f]{40} # v[0-9.]+' "$E2E_WF" || true)"
  build_push="${build_push%%$'\n'*}"
  [ -n "$build_push" ] || refuse "no pinned docker/build-push-action step in $E2E_WF to copy"
fi

# ---------------------------------------------------------------- staging (temp dir only)
STAGE="$(mktemp -d)"
edited=() created=() writing=0
rollback() {
  local f
  for f in "${edited[@]}"; do [ -f "$STAGE/backup/$f" ] && cp "$STAGE/backup/$f" "$f"; done
  for f in "${created[@]}"; do rm -rf "$f"; done
  echo "new-service: the write failed; rolled back (restored ${#edited[@]} edited files, removed the new paths)" >&2
}
cleanup() {
  local rc=$?
  if [ "$writing" = 1 ]; then rollback || true; fi
  rm -rf "$STAGE"
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
mkdir -p "$STAGE/new" "$STAGE/edit" "$STAGE/snip" "$STAGE/backup"

if [ "$mode" = prod ] && [ "$skip_pull" = 0 ]; then
  command -v docker >/dev/null 2>&1 \
    || refuse "docker is needed for the anonymous pull check (or check it yourself and pass --skip-pull-check)"
  mkdir -p "$STAGE/anon-docker" # empty DOCKER_CONFIG: no credentials, exactly what the prod cluster has
  if ! DOCKER_CONFIG="$STAGE/anon-docker" docker manifest inspect "$IMAGE_PREFIX/$name:main" >/dev/null 2>"$STAGE/pull.err"; then
    refuse "$IMAGE_PREFIX/$name:main is not pullable anonymously ($(head -c 300 "$STAGE/pull.err" | tr '\n' ' ')).
  The image must be published (first push to main) and the GHCR package public: Package settings -> Change
  visibility -> Public (docs/runbooks/go-live.md). Prod has no pull secret: an unpullable image makes
  verify-deploy fail and CD revert"
  fi
fi

render() { # render <src> <dst>: copy with every token replaced
  sed -e "s|__SERVICE__|$name|g" -e "s|__JAVA_PKG__|$pkg|g" -e "s|__CRATE__|$crate|g" -e "s|__PORT__|$port|g" \
    -e "s|__CLASS__|$class|g" -e "s|__ENV__|$env_prefix|g" -e "s|__BUILD_PUSH_ACTION__|$build_push|g" "$1" >"$2"
}
render_path() { # tokens in file and directory names
  printf '%s' "$1" | sed -e "s|__SERVICE__|$name|g" -e "s|__JAVA_PKG__|$pkg|g" -e "s|__CRATE__|$crate|g" \
    -e "s|__CLASS__|$class|g"
}
render_tree() { # render_tree <template dir> <dest dir under $STAGE/new>
  local src="$1" dst="$2" f rel out
  while IFS= read -r f; do
    rel="${f#"$src"/}"
    out="$dst/$(render_path "$rel")"
    mkdir -p "$(dirname "$out")"
    render "$f" "$out"
  done < <(find "$src" -type f -not -path '*/target/*')
}
staged() { # staged <repo file>: sets S to the path of its staged copy (created on first use). Never call it in a
  # command substitution: the subshell would lose the `edited` entry and the file would not be written back.
  S="$STAGE/edit/$1"
  if [ ! -f "$S" ]; then
    mkdir -p "$(dirname "$S")"
    cp "$1" "$S"
    edited+=("$1")
  fi
}
insert_before() { # insert_before <repo file> <point> <snippet file>: snippet lines above the marker line, same EOL
  local s tmpf
  staged "$1"; s="$S"
  tmpf="$s.tmp"
  awk -v BINMODE=3 -v re="$(marker_re "$2")" -v snip="$3" '
    $0 ~ re {
      eol = ($0 ~ /\r$/) ? "\r" : ""
      while ((getline l < snip) > 0) { sub(/\r$/, "", l); print l eol }
      close(snip)
    }
    { print }
  ' "$s" >"$tmpf"
  mv "$tmpf" "$s"
}
append_word() { # append_word <repo file> <point> <anchor> <word>: " word" before the first anchor on the marker line
  local s tmpf
  staged "$1"; s="$S"
  tmpf="$s.tmp"
  awk -v BINMODE=3 -v re="$(marker_re "$2")" -v anchor="$3" -v word="$4" '
    $0 ~ re {
      i = index($0, anchor)
      if (i == 0) { print "anchor not found" > "/dev/stderr"; exit 3 }
      $0 = substr($0, 1, i - 1) " " word substr($0, i)
    }
    { print }
  ' "$s" >"$tmpf" || refuse "cannot find '$3' on the 'new-service: $2' line of $1"
  mv "$tmpf" "$s"
}
snippet() { # snippet <name> <text...>: a staged snippet file from text (one line per argument)
  printf '%s\n' "${@:2}" >"$STAGE/snip/$1"
  printf '%s' "$STAGE/snip/$1"
}
snippet_tpl() { # snippet_tpl <template file>: a rendered registration snippet
  local out
  out="$STAGE/snip/$(basename "$1")"
  render "$1" "$out"
  printf '%s' "$out"
}

lock_note=""
if [ "$mode" = prod ]; then
  append_word "$CD_WF" cd-services " # new-service:" "$name"
  append_word "$CD_BUMP" cd-bump-services ")" "$name"
  insert_before "$PROD_K" prod-resources "$(snippet prod-res "  - ../../../base/$name")"
  insert_before "$PROD_K" prod-images "$(snippet prod-img "  - {name: $IMAGE_PREFIX/$name, newTag: main}")"
else
  # New files.
  created=("services/$name" "deploy/k8s/base/$name" "contracts/openapi/$name.yaml")
  render_tree "$TPL/$lang-service" "$STAGE/new/services/$name"
  mkdir -p "$STAGE/new/deploy/k8s/base/$name" "$STAGE/new/contracts/openapi"
  render "$TPL/k8s/kustomization.yaml" "$STAGE/new/deploy/k8s/base/$name/kustomization.yaml"
  render "$TPL/k8s/service.yaml" "$STAGE/new/deploy/k8s/base/$name/service.yaml"
  render "$TPL/k8s/deployment-$lang.yaml" "$STAGE/new/deploy/k8s/base/$name/deployment.yaml"
  render "$TPL/contracts/openapi.yaml" "$STAGE/new/contracts/openapi/$name.yaml"

  # CI, e2e, dependabot, compose, e2e Kustomize (not prod: see --prod).
  insert_before "$CI" ci-outputs "$(snippet ci-outputs "      $crate: \${{ steps.filter.outputs.$crate }}")"
  insert_before "$CI" ci-filters "$(snippet_tpl "$TPL/registration/ci-filter-$lang.yml")"
  insert_before "$CI" ci-jobs "$(snippet_tpl "$TPL/registration/ci-job-$lang.yml")"
  insert_before "$CI" ci-gate-needs "$(snippet ci-gate "      - $name")"
  insert_before "$E2E_WF" e2e-builds "$(snippet_tpl "$TPL/registration/e2e-build.yml")"
  append_word "$E2E_WF" e2e-load "; do" "$name"
  insert_before "$DEPENDABOT" dependabot "$(snippet_tpl "$TPL/registration/dependabot-$lang.yml")"
  insert_before "$COMPOSE" compose "$(snippet_tpl "$TPL/registration/compose-$lang.yml")"
  insert_before "$E2E_K" e2e-resources "$(snippet e2e-res "  - ../../base/$name")"
  insert_before "$E2E_K" e2e-images "$(snippet e2e-img "  - {name: $IMAGE_PREFIX/$name, newName: $name, newTag: e2e}")"

  if [ "$lang" = java ]; then
    insert_before pom.xml maven-modules "$(snippet module "    <module>services/$name</module>")"
    # Every Java image copies every reactor module pom (Maven reads the whole reactor even with -pl).
    staged pom.xml
    modules="$(sed -n 's|^[[:space:]]*<module>\([^<]*\)</module>.*|\1|p' "$S" | tr -d '\r')"
    for f in "${java_dockerfiles[@]}" "services/$name/Dockerfile"; do
      src="$f"
      [ "$f" = "services/$name/Dockerfile" ] && src="$STAGE/new/$f"
      lines=()
      while IFS= read -r m; do
        [ -n "$m" ] || continue
        grep -qxF "COPY $m/pom.xml $m/" <(tr -d '\r' <"$src") || lines+=("COPY $m/pom.xml $m/")
      done <<<"$modules"
      [ "${#lines[@]}" -gt 0 ] || continue
      snip="$(snippet "poms-$(printf '%s' "$f" | tr '/' '_')" "${lines[@]}")"
      if [ "$f" = "services/$name/Dockerfile" ]; then
        awk -v BINMODE=3 -v re="$(marker_re java-poms)" -v snip="$snip" '
          $0 ~ re { while ((getline l < snip) > 0) print l; close(snip) } { print }' "$src" >"$src.tmp"
        mv "$src.tmp" "$src"
      else
        insert_before "$f" java-poms "$snip"
      fi
    done
  else
    insert_before Cargo.toml cargo-members "$(snippet member "  \"services/$name\",")"
    # Cargo.lock: the new member's entry, its dependency lines copied from the existing workspace members' entries
    # (same versions, so `cargo --locked` accepts it). Without a match for every dependency, the lock is left alone.
    members_deps="$(tr -d '\r' <Cargo.lock | awk '
      /^\[\[package\]\]/ { if (n && !src) for (i = 1; i <= n; i++) print d[i]; n = 0; src = 0; indeps = 0; next }
      /^source = / { src = 1 }
      /^dependencies = \[/ { indeps = 1; next }
      indeps && /^\]/ { indeps = 0; next }
      indeps { d[++n] = $0 }
      END { if (n && !src) for (i = 1; i <= n; i++) print d[i] }
    ')"
    deps="$(awk '
      /^\[/ { sec = ($0 ~ /^\[(dev-)?dependencies\]/) }
      sec && /^[A-Za-z0-9_-]+[ .=]/ { k = $0; sub(/[ .=].*/, "", k); print k }
    ' "$STAGE/new/services/$name/Cargo.toml" | sort -u)"
    version="$(tr -d '\r' <Cargo.toml | awk '/^\[workspace.package\]/ { w = 1; next } /^\[/ { w = 0 } w && /^version *= *"/ { v = $0; sub(/^version *= *"/, "", v); sub(/".*/, "", v); print v }')"
    dep_lines=() missing=""
    while IFS= read -r d; do
      [ -n "$d" ] || continue
      hit="$(grep -E "^ \"$d( [^\"]+)?\",\$" <<<"$members_deps" || true)"
      hit="${hit%%$'\n'*}"
      if [ -n "$hit" ]; then dep_lines+=("$hit"); else missing="$missing $d"; fi
    done <<<"$deps"
    if [ -z "$missing" ] && [ -n "$version" ]; then
      entry="$STAGE/snip/lock-entry"
      {
        echo '[[package]]'
        echo "name = \"$name\""
        echo "version = \"$version\""
        echo 'dependencies = ['
        printf '%s\n' "${dep_lines[@]}" | sort -u
        echo ']'
        echo
      } >"$entry"
      staged Cargo.lock; s="$S"
      # Packages are sorted by name: insert before the first [[package]] whose name sorts after the new one, else
      # append (one blank line, then the entry without its trailing blank line).
      awk -v BINMODE=3 -v new="$name" -v entry="$entry" '
        { line[NR] = $0 }
        END {
          at = 0
          eol = (line[1] ~ /\r$/) ? "\r" : ""
          for (i = 1; i < NR; i++) {
            if (line[i] ~ /^\[\[package\]\]/ && line[i + 1] ~ /^name = "/) {
              n = line[i + 1]; sub(/^name = "/, "", n); sub(/".*/, "", n)
              if (n > new) { at = i; break }
            }
          }
          for (i = 1; i <= NR; i++) {
            if (i == at) { while ((getline l < entry) > 0) print l eol; close(entry) }
            print line[i]
          }
          if (at == 0) { print eol; while ((getline l < entry) > 0) if (l != "") print l eol; close(entry) }
        }
      ' "$s" >"$s.tmp"
      mv "$s.tmp" "$s"
    else
      lock_note="Cargo.lock was NOT updated (no locked version found for:${missing:- workspace version}): run scripts/cargo.sh update -w and commit Cargo.lock."
    fi
  fi
fi

# Every rendered or edited file is free of template tokens.
leftover="$(grep -rlE '__(SERVICE|JAVA_PKG|CRATE|PORT|CLASS|ENV|BUILD_PUSH_ACTION)__' "$STAGE/new" "$STAGE/edit" || true)"
[ -z "$leftover" ] || refuse "template tokens left in: ${leftover//$STAGE\//}"

# ---------------------------------------------------------------- write the tree (all checks passed)
# Back up every file about to be edited; from here on, a failure or interrupt rolls back (trap cleanup).
for f in "${edited[@]}"; do mkdir -p "$(dirname "$STAGE/backup/$f")"; cp "$f" "$STAGE/backup/$f"; done
writing=1
while IFS= read -r f; do
  f="${f#./}"
  mkdir -p "$(dirname "$f")"
  cp "$STAGE/new/$f" "$f"
done < <(cd "$STAGE/new" && find . -type f)
i=0
for f in "${edited[@]}"; do
  cp "$STAGE/edit/$f" "$f"
  i=$((i + 1))
  if [ "${NEW_SERVICE_TEST_FAIL_WRITE:-}" = 1 ] && [ "$i" = 1 ]; then
    echo "new-service: injected write failure (NEW_SERVICE_TEST_FAIL_WRITE)" >&2
    exit 1
  fi
done
writing=0

if [ "$mode" = prod ]; then
  echo "new-service: registered '$name' on the prod path:"
  for f in "${edited[@]}"; do echo "    $f"; done
  cat <<EOF

Next: commit, open a PR. After merge, Argo CD (prod-apps) deploys $IMAGE_PREFIX/$name and CD bumps its tag.
If the service is user-facing, add it to the image list in docs/runbooks/go-live.md.
EOF
  exit 0
fi

echo "new-service: created $lang service '$name' (port $port)"
echo "  new: services/$name/  deploy/k8s/base/$name/  contracts/openapi/$name.yaml"
echo "  registered in:"
for f in "${edited[@]}"; do echo "    $f"; done
[ -z "$lock_note" ] || echo "  WARNING: $lock_note"
if [ "$lang" = java ]; then
  verify="mvn -q -pl services/$name -am verify"
else
  verify="scripts/cargo.sh fmt --check && scripts/cargo.sh clippy -p $name --all-targets --locked -- -D warnings && scripts/cargo.sh test -p $name --locked"
fi
cat <<EOF

Next steps:
  1. Verify locally: $verify
  2. Read git diff --stat, replace the sample GET /v1/info (code + contracts/openapi/$name.yaml), commit, open a PR.
     It is NOT deployed to prod yet (no prod overlay entry, no CD bump).
  3. After that PR is merged and its ci run published $IMAGE_PREFIX/$name: GHCR package '$name' ->
     Package settings -> Change visibility -> Public (docs/runbooks/go-live.md).
  4. Then, in a follow-up PR: scripts/new-service.sh --prod $name   (checks the image is pullable anonymously,
     then registers the prod overlay, cd.yml and scripts/cd-bump.sh).
Not scaffolded (each needs judgment; copy from an existing service):
  - Database: Flyway (order-svc) or sqlx migrations (notify-svc), deploy/local/init-db.sql, the
    wait-for-postgres component in deploy/k8s/base/$name/kustomization.yaml, DB secrets below.
  - Kafka: topic declared by its producer (NewTopic bean), consumer idempotency (CLAUDE.md events rules).
  - Secrets: deploy/k8s/overlays/prod/secrets.tsv + scripts/seal-secrets.sh, e2e secretGenerator, envFrom.
  - Public route: deploy/k8s/overlays/prod/apps/ingress.yaml (+ the prod-ingress policy in scripts/k8s-validate.sh).
  - Smoke + e2e: scripts/smoke-$name.sh, then its port-forward and run line in scripts/k8s-e2e.sh and the job in
    scripts/e2e-observability.sh (probe_red_http) once the smoke sends it traffic.
  - Local memory: the service joins compose profile \`app\` (mem_limit $(if [ "$lang" = java ]; then echo 512m; else echo 64m; fi)); update the
    local stack budget line in CLAUDE.md, or move it to a profile of its own.
EOF
if [ "$lang" = java ]; then
  echo "  - scripts/k8s-validate.sh startup policy lists the Java images by name: add $name there (the rendered"
  echo "    deployment already has a 180 s startupProbe)."
fi
echo "  - Docs: CLAUDE.md Commands/repo map and docs/runbooks/go-live.md image list, if the service is user-facing."

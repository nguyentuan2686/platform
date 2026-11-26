#!/usr/bin/env bash
# Tests for the `bump` and `push` step scripts of .github/workflows/cd.yml (extracted with yq), offline against a local
# bare "origin". cd.yml only runs on main, so this is the pre-merge proof of the commit format and of the push retry:
# a rejected push re-runs cd-bump.sh on the new main and must never overwrite a newer tag (Review Focus 1).
# Prints "ok <case>" per case; exits non-zero on the first failure.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
wf="$root/.github/workflows/cd.yml"
kpath=deploy/k8s/overlays/prod/apps/kustomization.yaml
prefix=ghcr.io/nguyentuan2686

fail() { echo "FAIL $1: $2" >&2; exit 1; }

yq '.jobs.bump.steps[] | select(.id == "bump") | .run' "$wf" >"$tmp/bump-step.sh"
yq '.jobs.bump.steps[] | select(.id == "push") | .run' "$wf" >"$tmp/push-step.sh"
[ -s "$tmp/bump-step.sh" ] && [ -s "$tmp/push-step.sh" ] || fail extract "bump/push step scripts not found in cd.yml"

mkdir -p "$tmp/bin"
cat >"$tmp/bin/gh" <<'EOF'
#!/usr/bin/env bash
[ "$1 $2" = "api users/cd-bot[bot]" ] || { echo "unexpected gh $*" >&2; exit 1; }
echo 4242
EOF
chmod +x "$tmp/bin/gh"

g() { git -c user.name=t -c user.email=t@example.invalid -c commit.gpgsign=false "$@"; }
tag_in() { git -C "$1" show "origin/main:$kpath" 2>/dev/null | sed -n "s|.*{name: $prefix/$2, newTag: \([^}]*\)}.*|\1|p"; }

setup() { # fresh origin with main = A -> C; a seed clone to push "concurrent" commits; a runner clone at C
  rm -rf "$tmp/origin.git" "$tmp/seed" "$tmp/runner" "$tmp/rt"
  git init -q --bare "$tmp/origin.git"
  git -C "$tmp/origin.git" symbolic-ref HEAD refs/heads/main
  git init -q -b main "$tmp/seed"
  mkdir -p "$tmp/seed/scripts" "$tmp/seed/$(dirname "$kpath")"
  cp "$root/scripts/cd-bump.sh" "$tmp/seed/scripts/"
  printf '#!/usr/bin/env bash\necho "OK k8s-validate (stub) $*"\n' >"$tmp/seed/scripts/k8s-validate.sh"
  cp "$root/scripts/testdata/cd-bump/kustomization.yaml" "$tmp/seed/$kpath"
  g -C "$tmp/seed" add -A; g -C "$tmp/seed" commit -q -m A
  g -C "$tmp/seed" commit -q --allow-empty -m C; C="$(git -C "$tmp/seed" rev-parse HEAD)"
  git -C "$tmp/seed" remote add origin "$tmp/origin.git"
  git -C "$tmp/seed" push -q origin main
  git clone -q "$tmp/origin.git" "$tmp/runner"
  mkdir -p "$tmp/rt"
}

steps() { # run the bump step then the push step in the runner clone; sets rc, writes $tmp/out and $tmp/gh_out
  rc=0
  : >"$tmp/gh_out"
  (
    cd "$tmp/runner"
    export PATH="$tmp/bin:$PATH" RUNNER_TEMP="$tmp/rt" GITHUB_OUTPUT="$tmp/gh_out" SHA="$C" KUSTOMIZATION="$kpath" \
      PUBLISHED="order-svc web" APP_SLUG=cd-bot GH_TOKEN=x
    bash "$tmp/bump-step.sh"
    CHANGED="$(sed -n 's/^changed=//p' "$tmp/gh_out")" bash "$tmp/push-step.sh"
  ) >"$tmp/out" 2>&1 || rc=$?
}

push_commits_only_the_kustomization() {
  setup
  steps
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  git -C "$tmp/runner" fetch -q origin
  [ "$(tag_in "$tmp/runner" order-svc)" = "sha-$C" ] && [ "$(tag_in "$tmp/runner" web)" = "sha-$C" ] \
    || fail "${FUNCNAME[0]}" "origin not bumped: $(cat "$tmp/out")"
  [ "$(tag_in "$tmp/runner" chart-svc)" = main ] || fail "${FUNCNAME[0]}" "unpublished chart-svc changed"
  local files subject author
  files="$(git -C "$tmp/runner" show --name-only --format= origin/main)"
  [ "$files" = "$kpath" ] || fail "${FUNCNAME[0]}" "commit touches: $files"
  subject="$(git -C "$tmp/runner" log -1 --format=%s origin/main)"
  [ "$subject" = "deploy: order-svc,web to sha-${C:0:7} [skip ci]" ] || fail "${FUNCNAME[0]}" "subject: $subject"
  git -C "$tmp/runner" log -1 --format=%b origin/main | grep -qx "BUMP order-svc main -> sha-${C:0:7}" \
    || fail "${FUNCNAME[0]}" "body: $(git -C "$tmp/runner" log -1 --format=%b origin/main)"
  author="$(git -C "$tmp/runner" log -1 --format='%an <%ae>' origin/main)"
  [ "$author" = "cd-bot[bot] <4242+cd-bot[bot]@users.noreply.github.com>" ] || fail "${FUNCNAME[0]}" "author: $author"
  grep -qx 'pushed=true' "$tmp/gh_out" || fail "${FUNCNAME[0]}" "outputs: $(cat "$tmp/gh_out")"
  echo "ok ${FUNCNAME[0]}"
}

rejected_push_reapplies_on_new_main() { # an unrelated commit lands on main first: the bump is redone on top of it
  setup
  echo x >"$tmp/seed/README"; g -C "$tmp/seed" add README; g -C "$tmp/seed" commit -q -m unrelated
  git -C "$tmp/seed" push -q origin main
  steps
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -q '^push attempt 1 rejected' "$tmp/out" || fail "${FUNCNAME[0]}" "first push was not rejected: $(cat "$tmp/out")"
  git -C "$tmp/runner" fetch -q origin
  [ "$(tag_in "$tmp/runner" order-svc)" = "sha-$C" ] || fail "${FUNCNAME[0]}" "not bumped after retry"
  [ "$(git -C "$tmp/runner" log -1 --format=%s 'origin/main^')" = unrelated ] || fail "${FUNCNAME[0]}" "unrelated commit lost"
  echo "ok ${FUNCNAME[0]}"
}

retry_never_overwrites_a_newer_tag() { # Review Focus 1: CD for a later commit D won the race; C must not replace it
  setup
  g -C "$tmp/seed" commit -q --allow-empty -m D; local D; D="$(git -C "$tmp/seed" rev-parse HEAD)"
  sed -i "s|{name: $prefix/order-svc, newTag: main}|{name: $prefix/order-svc, newTag: sha-$D}|" "$tmp/seed/$kpath"
  g -C "$tmp/seed" commit -q -am "deploy: order-svc to sha-${D:0:7} [skip ci]"
  git -C "$tmp/seed" push -q origin main
  steps
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -qx "KEEP order-svc: current sha-$D is not an ancestor of $C" "$tmp/out" || fail "${FUNCNAME[0]}" "$(cat "$tmp/out")"
  git -C "$tmp/runner" fetch -q origin
  [ "$(tag_in "$tmp/runner" order-svc)" = "sha-$D" ] || fail "${FUNCNAME[0]}" "newer tag overwritten: $(tag_in "$tmp/runner" order-svc)"
  [ "$(tag_in "$tmp/runner" web)" = "sha-$C" ] || fail "${FUNCNAME[0]}" "web (still main on the new tip) not bumped"
  echo "ok ${FUNCNAME[0]}"
}

push_commits_only_the_kustomization
rejected_push_reapplies_on_new_main
retry_never_overwrites_a_newer_tag

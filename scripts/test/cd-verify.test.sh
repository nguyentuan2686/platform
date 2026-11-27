#!/usr/bin/env bash
# Tests for the `verify-deploy` job of .github/workflows/cd.yml (wiring checked and the issue step run offline with a
# stub gh), since cd.yml itself only runs on main. Prints "ok <case>" per case; exits non-zero on the first failure.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
wf="$root/.github/workflows/cd.yml"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
sha=0123456789abcdef0123456789abcdef01234567
bump=89abcdef0123456789abcdef0123456789abcdef

fail() { echo "FAIL $1: $2" >&2; exit 1; }
q() { yq -r "$1" "$wf"; }

job_wiring() {
  [ "$(q '.jobs["verify-deploy"].needs | join(",")')" = gate,bump ] || fail "${FUNCNAME[0]}" "needs: $(q '.jobs["verify-deploy"].needs')"
  [ "$(q '.jobs["verify-deploy"].if')" = "needs.bump.outputs.pushed == 'true' && needs.gate.outputs.verify == 'true'" ] \
    || fail "${FUNCNAME[0]}" "if: $(q '.jobs["verify-deploy"].if')"
  # Step order: tailnet, kubeconfig, kubectl, verify, then (on failure) App token, rollback, issue.
  local order
  order="$(q '.jobs["verify-deploy"].steps[] | (.id // .uses // .name)' | sed 's/@.*//' | paste -sd' ' -)"
  [[ "$order" == *"tailscale/github-action Prod kubeconfig Install kubectl"*" verify app mark rollback issue Remove the kubeconfig" ]] \
    || fail "${FUNCNAME[0]}" "step order: $order"
  [ "$(q '.jobs["verify-deploy"].steps[] | select(.uses == "tailscale/*") | .with.tags')" = tag:ci ] \
    || fail "${FUNCNAME[0]}" "tailscale tags not tag:ci"
  q '.jobs["verify-deploy"].steps[] | select(.id == "verify") | .run' >"$tmp/verify.sh"
  grep -q 'scripts/verify-deploy.sh --app prod-platform,prod-tunnel,prod-apps --revision "$BUMP_SHA"' "$tmp/verify.sh" \
    || fail "${FUNCNAME[0]}" "verify step: $(cat "$tmp/verify.sh")"
  [ "$(q '.jobs["verify-deploy"].env.BUMP_SHA')" = '${{ needs.bump.outputs.bump_sha }}' ] || fail "${FUNCNAME[0]}" "BUMP_SHA not from bump"
  [ "$(q ".jobs[\"verify-deploy\"].steps[] | select(.id == \"rollback\") | .if")" = "failure() && steps.verify.outputs.rc == '1'" ] \
    || fail "${FUNCNAME[0]}" "rollback must run only on verify exit 1 (unhealthy), never on 4 (unreachable)"
  [ "$(q ".jobs[\"verify-deploy\"].steps[] | select(.id == \"app\") | .if")" = "always() && (steps.verify.outcome == 'success' || steps.verify.outcome == 'failure')" ] \
    || fail "${FUNCNAME[0]}" "app token if"
  [ "$(q ".jobs[\"verify-deploy\"].steps[] | select(.id == \"mark\") | .if")" = "steps.verify.outcome == 'success'" ] \
    || fail "${FUNCNAME[0]}" "mark if"
  grep -q 'scripts/deploy-mark-verified.sh --sha "$BUMP_SHA" .*--push' <<<"$(q '.jobs["verify-deploy"].steps[] | select(.id == "mark") | .run')" \
    || fail "${FUNCNAME[0]}" "mark step does not run deploy-mark-verified.sh --push"
  [ "$(q ".jobs[\"verify-deploy\"].steps[] | select(.id == \"issue\") | .if")" = "failure() && (steps.verify.outcome == 'failure' || steps.mark.outcome == 'failure')" ] \
    || fail "${FUNCNAME[0]}" "issue step if"
  grep -q 'scripts/deploy-rollback.sh --sha "$BUMP_SHA" .*--push' <<<"$(q '.jobs["verify-deploy"].steps[] | select(.id == "rollback") | .run')" \
    || fail "${FUNCNAME[0]}" "rollback step does not run deploy-rollback.sh --push"
  grep -q -- '--bot "${APP_SLUG}\[bot\]"' <<<"$(q '.jobs["verify-deploy"].steps[] | select(.id == "rollback") | .run')" \
    || fail "${FUNCNAME[0]}" "rollback step does not pin the CD App with --bot"
  [ "$(q '.jobs["verify-deploy"].steps[] | select(.id == "issue") | .env.MARK_OUTCOME')" = '${{ steps.mark.outcome }}' ] \
    || fail "${FUNCNAME[0]}" "issue step does not get MARK_OUTCOME"
  echo "ok ${FUNCNAME[0]}"
}

unverified_deploy_warns() {
  local s
  s="$(q '.jobs.bump.steps[] | select(.name == "Deployed without verification")')"
  [ "$(yq -r '.if' <<<"$s")" = "steps.push.outputs.pushed == 'true' && needs.gate.outputs.verify != 'true'" ] \
    || fail "${FUNCNAME[0]}" "if: $(yq -r '.if' <<<"$s")"
  grep -q '::warning::deployed without verification: Tailscale/kubeconfig secrets not set' <<<"$(yq -r '.run' <<<"$s")" \
    || fail "${FUNCNAME[0]}" "message"
  echo "ok ${FUNCNAME[0]}"
}

issue_step() { # issue_step <verify rc> <rollback rc> [existing issue]: runs the issue step with a stub gh; argv per call in $tmp/gh.log
  mkdir -p "$tmp/bin" "$tmp/rt"
  cat >"$tmp/bin/gh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$GH_LOG"
[ "$1 $2" = "issue list" ] && { printf '%s' "${GH_EXISTING:-}"; exit 0; }
for a in "$@"; do [ "$prev" = --body-file ] && cp "$a" "$GH_LOG.body"; prev="$a"; done
EOF
  chmod +x "$tmp/bin/gh"
  : >"$tmp/gh.log"
  seq 1 150 | sed 's/^/verify line /' >"$tmp/rt/verify.out"
  q '.jobs["verify-deploy"].steps[] | select(.id == "issue") | .run' >"$tmp/issue.sh"
  rc=0
  env -i PATH="$tmp/bin:$PATH" GH_LOG="$tmp/gh.log" RUNNER_TEMP="$tmp/rt" SHA="$sha" BUMP_SHA="$bump" \
    RUN_URL=https://github.com/o/platform/actions/runs/42 VERIFY_RC="$1" ROLLBACK_RC="$2" GH_EXISTING="${3:-}" \
    MARK_OUTCOME="${MARK:-skipped}" bash "$tmp/issue.sh" >"$tmp/out" 2>&1 || rc=$?
}

issue_has_title_label_run_and_log_tail() {
  issue_step 1 0
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -q '^label create deploy-failed .*--force' "$tmp/gh.log" || fail "${FUNCNAME[0]}" "label not created: $(cat "$tmp/gh.log")"
  grep -q "^issue create --title deploy failed: sha-${sha:0:7} --label deploy-failed --body-file " "$tmp/gh.log" \
    || fail "${FUNCNAME[0]}" "issue args: $(cat "$tmp/gh.log")"
  local body="$tmp/gh.log.body"
  grep -q 'https://github.com/o/platform/actions/runs/42' "$body" || fail "${FUNCNAME[0]}" "no run URL"
  grep -q '^- Rolled back' "$body" || fail "${FUNCNAME[0]}" "no rollback line"
  grep -qx 'verify line 51' "$body" && grep -qx 'verify line 150' "$body" || fail "${FUNCNAME[0]}" "log tail missing"
  if grep -qx 'verify line 50' "$body"; then fail "${FUNCNAME[0]}" "more than 100 log lines"; fi
  issue_step 1 3
  grep -q '^- NOT rolled back: the revert conflicted' "$tmp/gh.log.body" || fail "${FUNCNAME[0]}" "conflict not reported"
  grep -qx "~~~~" "$tmp/gh.log.body" || fail "${FUNCNAME[0]}" "log not fenced with ~~~~"
  issue_step 1 4
  grep -q "^- NOT rolled back by this run: a newer deploy commit is on main. The newest CD run owns the outcome: if its verify-deploy fails too, it reverts every deploy since the last verified one" "$tmp/gh.log.body" || fail "${FUNCNAME[0]}" "superseded not reported"
  echo "ok ${FUNCNAME[0]}"
}

unreachable_opens_or_updates_unverified_issue() {
  # Review I1(b): exit 4 never reverts; it opens one deploy-unverified issue, later runs comment on it.
  issue_step 4 ""
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -q "^issue create --title deploy unverified: prod cluster unreachable --label deploy-unverified " "$tmp/gh.log" \
    || fail "${FUNCNAME[0]}" "create: $(cat "$tmp/gh.log")"
  grep -q "^- NOT rolled back: verify-deploy could not reach the cluster" "$tmp/gh.log.body" || fail "${FUNCNAME[0]}" "wording"
  issue_step 4 "" 17
  grep -q "^issue comment 17 --body-file " "$tmp/gh.log" || fail "${FUNCNAME[0]}" "no comment on #17: $(cat "$tmp/gh.log")"
  if grep -q "^issue create" "$tmp/gh.log"; then fail "${FUNCNAME[0]}" "opened a second issue"; fi
  echo "ok ${FUNCNAME[0]}"
}

mark_failure_opens_an_issue() {
  # Final review M2: verified, but the deploy-verified tag push failed; a later rollback would go back past this deploy.
  MARK=failure issue_step 0 ""
  [ "$rc" -eq 0 ] || fail "${FUNCNAME[0]}" "rc=$rc: $(cat "$tmp/out")"
  grep -q "^issue create --title deploy verified but not recorded: sha-${sha:0:7} --label deploy-unrecorded " "$tmp/gh.log" \
    || fail "${FUNCNAME[0]}" "create: $(cat "$tmp/gh.log")"
  grep -q '^- Verified, but the deploy-verified tag was not moved' "$tmp/gh.log.body" || fail "${FUNCNAME[0]}" "wording: $(cat "$tmp/gh.log.body")"
  echo "ok ${FUNCNAME[0]}"
}

job_wiring
unverified_deploy_warns
issue_has_title_label_run_and_log_tail
unreachable_opens_or_updates_unverified_issue
mark_failure_opens_an_issue

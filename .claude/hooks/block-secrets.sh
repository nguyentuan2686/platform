#!/usr/bin/env bash
# PreToolUse(Bash): block `git commit` when staged added lines contain secrets (exit 2).
input="$(cat)"
case "$input" in
  *"git commit"*) ;;
  *) exit 0 ;;
esac

patterns='-----BEGIN .*PRIVATE KEY-----|ghp_[A-Za-z0-9]{36}|gho_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|xox[bp]-[A-Za-z0-9-]+'
cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0

current=""
hits=""
while IFS= read -r line; do
  case "$line" in
    "+++ b/"*) current="${line#+++ b/}" ;;
    "+++"*) ;;
    "+"*)
      if printf '%s\n' "${line:1}" | grep -Eq -e "$patterns"; then
        case " $hits " in *" $current "*) ;; *) hits="$hits $current" ;; esac
      fi
      ;;
  esac
done < <(git diff --cached -U0 --no-color 2>/dev/null)

if [ -n "$hits" ]; then
  echo "BLOCKED: possible secret in staged changes:$hits" >&2
  echo "Remove it (use env vars / Sealed Secrets), unstage the file, then commit again." >&2
  exit 2
fi
exit 0

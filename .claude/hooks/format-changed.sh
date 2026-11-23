#!/usr/bin/env bash
# PostToolUse(Edit|Write): auto-format the edited file. Never blocks (always exit 0).
input="$(cat)"
file="$(printf '%s' "$input" | sed -nE 's/.*"file_path" *: *"([^"]*)".*/\1/p' | head -n1)"
file="$(printf '%s' "$file" | tr '\134' '/' | sed 's|//*|/|g')"   # JSON-escaped backslashes -> single slash
[ -z "$file" ] && exit 0
[ -f "$file" ] || exit 0

case "$file" in
  *.java)
    dir="$(dirname "$file")"
    while [ -n "$dir" ] && [ "$dir" != "/" ] && [ "$dir" != "." ] && [ ! -f "$dir/pom.xml" ]; do
      parent="$(dirname "$dir")"
      [ "$parent" = "$dir" ] && break
      dir="$parent"
    done
    if [ -f "$dir/pom.xml" ]; then
      if command -v mvn >/dev/null 2>&1; then
        if command -v timeout >/dev/null 2>&1; then
          timeout 120 mvn -q -f "$dir/pom.xml" spotless:apply >/dev/null 2>&1
        else
          mvn -q -f "$dir/pom.xml" spotless:apply >/dev/null 2>&1
        fi
      fi
    fi
    ;;
  *.rs)
    if command -v rustfmt >/dev/null 2>&1; then
      rustfmt "$file" >/dev/null 2>&1
    else
      # No native Rust: format the workspace via the Docker toolchain, only if Docker is up and the image exists.
      root="$(cd "$(dirname "$0")/../.." && pwd)"
      tc="$(sed -nE 's/^channel *= *"([^"]+)".*/\1/p' "$root/rust-toolchain.toml" 2>/dev/null | head -n1)"
      if [ -n "$tc" ] && command -v docker >/dev/null 2>&1 \
         && timeout 5 docker image inspect "platform-rust-dev:$tc" >/dev/null 2>&1; then
        timeout 60 "$root/scripts/cargo.sh" fmt --all >/dev/null 2>&1
      fi
    fi
    ;;
esac
exit 0

#!/usr/bin/env bash
# Run cargo inside the dockerized Rust toolchain (no native Rust needed).
# Usage: scripts/cargo.sh <cargo args>   e.g. scripts/cargo.sh clippy --all-targets -- -D warnings
# Works from Git Bash (Windows) and Linux. Container runs as root (avoids uid mapping on Docker Desktop).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TOOLCHAIN="$(sed -nE 's/^channel *= *"([^"]+)".*/\1/p' rust-toolchain.toml | head -n1)"
IMAGE="platform-rust-dev:${TOOLCHAIN}"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    export MSYS_NO_PATHCONV=1
    WORK="$(pwd -W)"
    SOCK="//var/run/docker.sock"
    ;;
  *)
    WORK="$(pwd)"
    SOCK="/var/run/docker.sock"
    ;;
esac

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "Building $IMAGE (first use, takes several minutes)..." >&2
  docker build -t "$IMAGE" tools/rust-dev >&2
fi

TTY=()
[ -t 0 ] && [ -t 1 ] && TTY=(-t)

exec docker run --rm -i "${TTY[@]}" \
  -v "$WORK:/work" \
  -v platform-cargo-registry:/usr/local/cargo/registry \
  -v platform-cargo-git:/usr/local/cargo/git \
  -v "$SOCK:/var/run/docker.sock" \
  -e CARGO_TARGET_DIR=/work/target/docker \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -e RUST_LOG="${RUST_LOG:-}" \
  -e RUST_BACKTRACE="${RUST_BACKTRACE:-}" \
  -w /work \
  "$IMAGE" cargo "$@"

# Dev image for all Rust work (no native Rust on the dev machine). Run via scripts/cargo.sh.
# Keep the rust tag in sync with rust-toolchain.toml.
FROM rust:1.100.0-bookworm

ARG CARGO_BINSTALL_VERSION=1.28.0
ARG CARGO_DENY_VERSION=0.22.0
ARG CARGO_LLVM_COV_VERSION=0.9.2
ARG CARGO_CHEF_VERSION=0.1.78

RUN apt-get update \
 && apt-get install -y --no-install-recommends cmake build-essential pkg-config libssl-dev libsasl2-dev ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*

RUN rustup component add rustfmt clippy llvm-tools-preview

# Prebuilt binaries via pinned cargo-binstall (fast on 2 cores); falls back to `cargo install --locked` from source.
RUN arch="$(uname -m)" \
 && curl -fsSL "https://github.com/cargo-bins/cargo-binstall/releases/download/v${CARGO_BINSTALL_VERSION}/cargo-binstall-${arch}-unknown-linux-musl.tgz" | tar -xz -C /usr/local/cargo/bin \
 && for spec in cargo-deny@${CARGO_DENY_VERSION} cargo-llvm-cov@${CARGO_LLVM_COV_VERSION} cargo-chef@${CARGO_CHEF_VERSION}; do \
      cargo binstall --no-confirm --locked "$spec" || cargo install --locked "${spec%@*}" --version "${spec#*@}" || exit 1; \
    done \
 && rm -rf /usr/local/cargo/registry /usr/local/cargo/git

# Runs as root (Docker Desktop on Windows: avoids uid mapping on bind mounts).
WORKDIR /work

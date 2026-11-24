# Build context: repo root.  docker build -f services/notify-svc/Dockerfile -t notify-svc:local .
# Keep the rust tag in sync with rust-toolchain.toml.
FROM rust:1.100.0-bookworm AS chef
ARG CARGO_BINSTALL_VERSION=1.28.0
ARG CARGO_CHEF_VERSION=0.1.78
# cmake + C++ toolchain: rdkafka builds librdkafka from source (cmake-build feature).
RUN apt-get update \
 && apt-get install -y --no-install-recommends cmake build-essential pkg-config ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*
RUN arch="$(uname -m)" \
 && curl -fsSL "https://github.com/cargo-bins/cargo-binstall/releases/download/v${CARGO_BINSTALL_VERSION}/cargo-binstall-${arch}-unknown-linux-musl.tgz" | tar -xz -C /usr/local/cargo/bin \
 && (cargo binstall --no-confirm --locked "cargo-chef@${CARGO_CHEF_VERSION}" \
     || cargo install --locked cargo-chef --version "${CARGO_CHEF_VERSION}") \
 && rm -rf /usr/local/cargo/registry /usr/local/cargo/git
WORKDIR /src

FROM chef AS planner
COPY . .
RUN cargo chef prepare --recipe-path recipe.json

FROM chef AS builder
# Pins the toolchain so rustup does not re-download components listed in rust-toolchain.toml.
ENV RUSTUP_TOOLCHAIN=1.100.0
COPY --from=planner /src/recipe.json recipe.json
# Dependencies only: cached until Cargo.toml/Cargo.lock change.
RUN cargo chef cook --release --locked --recipe-path recipe.json -p notify-svc
COPY . .
RUN cargo build --release --locked -p notify-svc
# `ldd` shows libz.so.1 is the only runtime lib missing from distroless/cc; stage it (arch-independent glob).
RUN mkdir /runtime-libs && cp -L /lib/*-linux-gnu/libz.so.1 /runtime-libs/

# distroless/cc: glibc, libgcc, libstdc++ (librdkafka is statically linked); non-root uid 65532.
FROM gcr.io/distroless/cc-debian12:nonroot
COPY --from=builder /runtime-libs/libz.so.1 /usr/lib/libz.so.1
COPY --from=builder /src/target/release/notify-svc /app/notify-svc
ENV PORT=8081 \
    OTEL_SERVICE_NAME=notify-svc
USER 65532:65532
EXPOSE 8081
# No HEALTHCHECK: orchestrator probes /health/live and /health/ready.
ENTRYPOINT ["/app/notify-svc"]

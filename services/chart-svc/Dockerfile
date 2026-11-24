# Build context: repo root.  docker build -f services/chart-svc/Dockerfile -t chart-svc:local .
# Keep the rust tag in sync with rust-toolchain.toml.
FROM rust:1.100.0-bookworm AS chef
ARG CARGO_BINSTALL_VERSION=1.28.0
ARG CARGO_CHEF_VERSION=0.1.78
# cmake + C++ toolchain: platform-core depends on rdkafka, which builds librdkafka from source (cmake-build).
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
RUN cargo chef cook --release --locked --recipe-path recipe.json -p chart-svc
COPY . .
RUN cargo build --release --locked -p chart-svc
# ldd output stays in the build log: only libc/libm/libgcc_s are needed, all in distroless/cc (no libz, unlike notify-svc).
RUN ldd target/release/chart-svc

# distroless/cc: glibc, libgcc; non-root uid 65532. jiff embeds the tz database, so no zoneinfo needed.
FROM gcr.io/distroless/cc-debian12:nonroot
COPY --from=builder /src/target/release/chart-svc /app/chart-svc
ENV PORT=8082 \
    OTEL_SERVICE_NAME=chart-svc
USER 65532:65532
EXPOSE 8082
# No HEALTHCHECK: orchestrator probes /health/live and /health/ready.
ENTRYPOINT ["/app/chart-svc"]

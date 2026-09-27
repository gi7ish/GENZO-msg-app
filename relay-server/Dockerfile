# Builds ONLY the `relay` binary — verified (by inspecting relay/Cargo.toml
# and common/Cargo.toml) to depend on nothing but pure-Rust crates (serde,
# serde_json), so this needs no cmake/protoc/clang the way the full
# workspace's device/local_store crates do. Cargo will still see the whole
# workspace Cargo.lock while resolving, but only compiles what `-p relay`
# actually depends on.
#
# UNVERIFIED — this Dockerfile has not been built anywhere. First real test
# is whatever platform (Render, Railway, Fly.io, etc.) actually runs it.

FROM rust:1.82-slim-bookworm AS builder
WORKDIR /app
COPY . .
RUN cargo build --release -p relay

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/target/release/relay /usr/local/bin/relay

# Cloud platforms (Render, Railway, etc.) inject their own $PORT and expect
# the app to bind 0.0.0.0:$PORT. Falls back to 8765 if unset (local `docker
# run` with no -e PORT=...).
ENV PORT=8765
EXPOSE 8765
CMD ["sh", "-c", "RELAY_ADDR=0.0.0.0:${PORT:-8765} relay"]

# TeamAccess Remote — Root-level Dockerfile for Render
# Builds the Rust signaling server from server/ subdirectory

FROM rust:alpine AS builder

RUN apk add --no-cache musl-dev

WORKDIR /app

# Copy server files
COPY server/Cargo.toml server/Cargo.lock ./

# Dummy build to cache dependencies
RUN mkdir -p src public \
    && echo "fn main() {}" > src/main.rs \
    && echo "" > public/index.html \
    && cargo build --release \
    && rm -rf src

# Copy actual source + embedded HTML
COPY server/src ./src
COPY server/public ./public

# Touch main.rs to force rebuild with new code
RUN touch src/main.rs && cargo build --release

# Minimal runtime image — no file system needed (HTML is embedded in binary)
FROM alpine:3.20 AS runner
RUN apk add --no-cache ca-certificates

WORKDIR /app
COPY --from=builder /app/target/release/teamaccess-signaling-server ./server

ENV PORT=9090
EXPOSE 9090

CMD ["./server"]

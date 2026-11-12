# Multi-stage Rust build optimized for Render & minimal memory footprint (< 15MB)
FROM rust:alpine AS builder

RUN apk add --no-cache musl-dev

WORKDIR /app
COPY Cargo.toml ./

# Dummy build to cache dependencies
RUN mkdir src && echo "fn main() {}" > src/main.rs && cargo build --release && rm -rf src

# Build actual application
COPY src ./src
RUN touch src/main.rs && cargo build --release

# Minimal runtime image
FROM alpine:3.20 AS runner
RUN apk add --no-cache ca-certificates

WORKDIR /app
COPY --from=builder /app/target/release/teamaccess-signaling-server ./server
COPY public ./public

ENV PORT=9090
EXPOSE 9090

CMD ["./server"]

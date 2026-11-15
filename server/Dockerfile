# Multi-stage Rust build — HTML is embedded in binary via include_str!
FROM rust:alpine AS builder

RUN apk add --no-cache musl-dev

WORKDIR /app
COPY Cargo.toml Cargo.lock ./

# Dummy build to cache dependencies
RUN mkdir -p src public && echo "fn main() {}" > src/main.rs && echo "" > public/index.html && cargo build --release && rm -rf src

# Copy actual source + embedded HTML
COPY src ./src
COPY public ./public
RUN touch src/main.rs && cargo build --release

# Minimal runtime image
FROM alpine:3.20 AS runner
RUN apk add --no-cache ca-certificates

WORKDIR /app
COPY --from=builder /app/target/release/teamaccess-signaling-server ./server

ENV PORT=9090
EXPOSE 9090

CMD ["./server"]

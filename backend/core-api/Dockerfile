# Multi-stage: the build image carries a full Go toolchain, the final one carries a single
# static binary and nothing else — no shell, no package manager, no libc to patch.
FROM golang:1.27-alpine AS build
WORKDIR /src

# Copy the module files first so dependency download is cached independently of source edits.
COPY go.mod go.sum ./
RUN go mod download

COPY . .
# CGO off is what makes the binary static, which is what lets the final stage be distroless.
# -trimpath keeps build-machine paths out of the binary; -s -w drops the symbol table.
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/api ./cmd/api

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/api /api
# Non-root by default. The service opens no files and needs no writable filesystem: state
# lives in Postgres, and migrations are embedded in the binary.
USER nonroot:nonroot
EXPOSE 8080
ENTRYPOINT ["/api"]

# yacy-sidecar: libp2p NAT traversal next to a YaCy peer (see docs/trust-and-nat.md)
#   docker build -t yacy-lab/sidecar:latest sidecar/
FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum* ./
RUN go mod download || true
COPY . .
RUN go mod tidy && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /yacy-sidecar .

FROM alpine:3.22
RUN adduser -D -H sidecar
COPY --from=build /yacy-sidecar /usr/local/bin/yacy-sidecar
USER sidecar
EXPOSE 4001 8095
ENTRYPOINT ["/usr/local/bin/yacy-sidecar"]

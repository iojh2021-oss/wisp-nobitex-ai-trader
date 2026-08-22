FROM golang:1.26.5 AS build

WORKDIR /src/wisp
COPY wisp/go.mod ./
RUN go mod download
COPY wisp/ ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o /out/wisp .

FROM gcr.io/distroless/static-debian12:nonroot
WORKDIR /app
COPY --from=build /out/wisp /app/wisp
COPY --from=build /src/wisp/config.yml /app/config.yml
ENV PORT=10000
EXPOSE 10000
USER nonroot:nonroot
ENTRYPOINT ["/app/wisp", "-config", "/app", "-wisp", "/app/config.yml"]

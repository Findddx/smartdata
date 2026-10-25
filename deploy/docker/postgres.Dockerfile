ARG REGISTRY=docker.io
FROM ${REGISTRY}/library/golang:1.27.3-alpine3.24@sha256:b8c9b82affbbb82a020bbcf998dad2ba9516944ac7e883524642a481a9505e9c AS gosu-build
ARG GOPROXY=https://proxy.golang.org
ENV GOTOOLCHAIN=local CGO_ENABLED=0 GOMAXPROCS=2 GOMEMLIMIT=2GiB \
    GOPROXY=${GOPROXY} GOSUMDB=sum.golang.org
ADD --checksum=sha256:33d7537d588ea49458b9509bcf4554bdf5ceacc66da71e5caa1058ea3b689c3b \
    https://codeload.github.com/tianon/gosu/tar.gz/6456aaa0f3c854d199d0f037f068eb97515b7513 /tmp/gosu.tar.gz
WORKDIR /src/gosu
RUN set -eu; \
    test "$(go env GOVERSION)" = go1.27.3; \
    tar -xzf /tmp/gosu.tar.gz --strip-components=1; \
    rm /tmp/gosu.tar.gz; \
    go mod download; \
    go mod verify; \
    mkdir -p /out/licenses; \
    go build -mod=readonly -trimpath -buildvcs=false -ldflags='-w' -o /out/gosu .; \
    cp LICENSE /out/licenses/gosu-APACHE-2.0; \
    cp /usr/local/go/LICENSE /out/licenses/go-BSD-3-Clause; \
    cp /go/pkg/mod/github.com/moby/sys/user@v0.1.0/LICENSE /out/licenses/moby-sys-user-APACHE-2.0; \
    cp /go/pkg/mod/golang.org/x/sys@v0.1.0/LICENSE /out/licenses/x-sys-BSD-3-Clause; \
    printf '%s\n' \
      'gosu 1.19; unchanged upstream source commit 6456aaa0f3c854d199d0f037f068eb97515b7513.' \
      'SmartData rebuilt this binary with Go 1.27.3; it differs from the upstream release binary.' \
      'Go module checksums are verified with the upstream go.sum and sum.golang.org.' \
      > /out/licenses/NOTICE
ADD --checksum=sha256:98d35d11a78dc453aebb7d10c3748f3f73ff78c8267c0dc697b8631f67bf7e8f \
    https://codeload.github.com/google/brotli/tar.gz/refs/tags/v1.2.1 /tmp/brotli-source.tar.gz
RUN set -eu; \
    tar -xOf /tmp/brotli-source.tar.gz brotli-1.2.1/LICENSE > /out/licenses/brotli-MIT; \
    rm /tmp/brotli-source.tar.gz

FROM ${REGISTRY}/library/postgres:18.6-alpine3.24@sha256:898c60cb390d1085bc97bdd6a1ae968757421df394b071848b52b1c09c89d6a5
LABEL io.smartdata.postgres.base="postgres:18.6-alpine3.24@sha256:898c60cb390d1085bc97bdd6a1ae968757421df394b071848b52b1c09c89d6a5" \
      io.smartdata.gosu.source-commit="6456aaa0f3c854d199d0f037f068eb97515b7513" \
      io.smartdata.gosu.go-version="1.27.3" \
      io.smartdata.brotli.version="1.2.1-r0"
# Keep PostgreSQL 18, its entrypoint, PGDATA, volume and privilege-drop behavior.
# Pin both the Alpine package version and its exact signed archive bytes.
RUN set -eu; \
    arch="$(apk --print-arch)"; \
    case "$arch" in \
      x86_64) checksum=62a7e37e43bbfc1f8525a00ad2125fd5897135dc93b849733884cb78ffd8e26c ;; \
      aarch64) checksum=f802b9d5a329c216f02e73607cb8f662c434767e41686367ac0afcf6d0d45d64 ;; \
      *) printf 'This patched image currently supports amd64/arm64; got %s\n' "$arch" >&2; exit 1 ;; \
    esac; \
    wget -O /tmp/brotli-libs.apk "https://dl-cdn.alpinelinux.org/alpine/v3.24/main/$arch/brotli-libs-1.2.1-r0.apk"; \
    printf '%s  /tmp/brotli-libs.apk\n' "$checksum" | sha256sum -c -; \
    apk verify /tmp/brotli-libs.apk; \
    apk add --no-cache --no-network /tmp/brotli-libs.apk; \
    apk info --exists 'brotli-libs=1.2.1-r0'; \
    rm /tmp/brotli-libs.apk
COPY --from=gosu-build /out/gosu /usr/local/bin/gosu
COPY --from=gosu-build /out/licenses/ /usr/local/share/licenses/smartdata-postgres/
RUN set -eu; \
    gosu --version | grep -F '1.19 (go1.27.3 on linux/'; \
    test "$(gosu postgres id -u)" = 70; \
    postgres --version | grep -Fx 'postgres (PostgreSQL) 18.6'
USER postgres

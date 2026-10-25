# SmartData MCP bridge

The bridge is a deliberately thin adapter between MCP clients and the typed
SmartData runtime API. It exposes exactly three tools:

- `smartdata_get_case` — `GET /api/v1/cases/{caseId}`
- `smartdata_list_case_evidence` — `GET /api/v1/cases/{caseId}/evidence`
- `smartdata_create_action_request` — `POST /api/v1/action-requests`

The write-shaped tool only creates an approval-gated request. There is no MCP
tool that executes, compensates, approves, verifies, changes a customer
commitment, or accesses SQL/shell/files.

The service implements Streamable HTTP at `/mcp` with the official MCP Go SDK
v1.7.0. It runs statelessly, supports protocol `2026-07-28`, and retains the
SDK's negotiated compatibility with earlier revisions. Liveness and readiness
are served on a different listener at `/livez` and `/readyz`.

## Security boundary

- The runtime URL has no user-controlled component. Its configured hostname
  must also appear in `SMARTDATA_RUNTIME_ALLOWED_HOSTS`.
- HTTPS is mandatory unless the explicit local-only HTTP switch is enabled.
- Redirects are rejected, so bearer credentials cannot follow an upstream
  redirect.
- `Authorization` is copied only to the configured runtime origin. The bridge
  checks bearer syntax; the runtime remains the authority that verifies the
  JWT and derives tenant, actor and roles.
- `X-Tenant-Id` and `X-Roles` are always rejected. `X-Demo-Actor` is accepted
  only when demo mode and its actor allowlist are both explicitly configured.
- Bodies, headers, bearer values, identifiers and upstream response bodies are
  absent from structured access logs.
- Query results use minimized MCP-specific views: claim values, subject IDs,
  object URIs and source-record IDs are not returned. User read permission is
  still not a data-export decision; keep the production bridge disabled until
  a destination/model/purpose/classification-aware OPA egress check is wired.
- Incoming bodies, upstream responses, request duration and global concurrency
  are bounded. CORS is disabled; requests carrying `Origin` are denied unless
  that exact origin is configured.
- `/readyz` performs a bounded Runtime readiness check; `/livez` reports only
  the bridge process state.

Terminate public TLS and authenticate clients at an approved gateway. The
bridge does not issue cookies, host diagnostic handlers, accept dynamic URLs,
or trust forwarded client-IP headers.

## Configuration

| Environment variable | Default | Notes |
|---|---:|---|
| `SMARTDATA_RUNTIME_BASE_URL` | required | Scheme and authority only |
| `SMARTDATA_RUNTIME_ALLOWED_HOSTS` | required | Comma-separated exact hostnames |
| `SMARTDATA_MCP_LISTEN_ADDR` | `127.0.0.1:8082` | Explicit host and port |
| `SMARTDATA_MCP_HEALTH_LISTEN_ADDR` | `127.0.0.1:8083` | Must differ from MCP listener |
| `SMARTDATA_MCP_ALLOWED_ORIGINS` | empty | Empty rejects every browser Origin |
| `SMARTDATA_MCP_ALLOW_INSECURE_UPSTREAM` | `false` | Local demo only |
| `SMARTDATA_MCP_ALLOW_DEMO_AUTH` | `false` | Local demo only |
| `SMARTDATA_MCP_ALLOWED_DEMO_ACTORS` | empty | Required if demo auth is enabled |
| `SMARTDATA_MCP_MAX_REQUEST_BYTES` | `262144` | Range: 1 KiB–4 MiB |
| `SMARTDATA_MCP_MAX_RESPONSE_BYTES` | `1048576` | Range: 1 KiB–8 MiB |
| `SMARTDATA_MCP_MAX_CONCURRENT_REQUESTS` | `64` | Immediate 429 when saturated |
| `SMARTDATA_MCP_UPSTREAM_TIMEOUT` | `10s` | Range: 1–60 seconds |
| `SMARTDATA_MCP_SHUTDOWN_TIMEOUT` | `10s` | Range: 1–60 seconds |
| `SMARTDATA_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |

The action-request tool requires `recommendationId`, non-negative
`expectedStateVersion`, and a caller-stable `idempotencyKey` of 16–128 safe
characters. The bridge sends the latter as the runtime `Idempotency-Key`
header and refuses mismatching header/argument values.

## Development

```text
go mod download
go test ./...
go test -race ./...
go vet ./...
```

The container is a static, non-root scratch image. Pin its resulting digest in
production rather than deploying a mutable tag.

# Local demo

`deploy/compose/compose.demo.yaml` is an isolated developer topology, not a
production deployment. It binds every published port to `127.0.0.1`, uses a
private Docker network, requires operator-supplied credentials and does not
enable broker/database TLS inside the local machine.

## Start

Copy `deploy/compose/demo.env.example` to the ignored file
`deploy/compose/demo.env`, then generate three independent random hex values.
For example, in PowerShell:

```powershell
$bytes = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToHexString($bytes).ToLowerInvariant()
```

Populate the database, Qdrant and OpenFGA values with separately generated
outputs. Validate and start the dependency layer:

```text
docker compose --env-file deploy/compose/demo.env -f deploy/compose/compose.demo.yaml config --quiet
docker compose --env-file deploy/compose/demo.env -f deploy/compose/compose.demo.yaml up -d
```

Start all demonstration containers after the Gradle and npm lockfiles are
present:

```text
docker compose --env-file deploy/compose/demo.env --profile apps --profile observability -f deploy/compose/compose.demo.yaml up --build
```

Default loopback endpoints are:

| Component | Endpoint |
|---|---|
| Runtime API | `http://127.0.0.1:8080` |
| MCP | `http://127.0.0.1:8082/mcp` |
| MCP health | `http://127.0.0.1:8083/livez` |
| Temporal | `127.0.0.1:7233` |
| Temporal UI | `http://127.0.0.1:8233` |
| PostgreSQL | `127.0.0.1:5432` |
| Kafka | `127.0.0.1:29092` |
| Qdrant | `http://127.0.0.1:6333` |
| OPA | `http://127.0.0.1:8181` |
| OpenFGA | `http://127.0.0.1:8088` |
| Console | `http://127.0.0.1:4173` |

The current `local` runtime intentionally uses in-memory adapters. PostgreSQL,
Kafka, Qdrant, OPA and OpenFGA are present to establish and test the deployment
boundary; they are not represented as wired production adapters. The workflow
worker can connect to Temporal when `SMARTDATA_TEMPORAL_ENABLED=true`, but the
Runtime API does not yet dispatch its action requests into that worker.

Local requests use one of the server-defined actors such as
`X-Demo-Actor: order-specialist`. Never enable demo authentication outside this
loopback topology. Tenant and role headers are rejected rather than trusted.

## Stop and reset

Stop while preserving data with `docker compose ... down`. To deliberately
reset the demo, use `docker compose ... down --volumes`; this permanently
deletes only the named demo volumes shown by `docker compose ... config`.

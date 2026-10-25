# Event contract

Kafka records use the CloudEvents JSON format defined by
`smartdata-event.schema.json`. Producers write aggregate changes and an outbox
row in one PostgreSQL transaction. Consumers claim an inbox row keyed by
`tenantid + consumerName + event id` before applying a projection.

The Kafka record key is `tenantid + ":" + aggregateid`; this preserves order
inside one tenant aggregate without claiming atomic exactly-once behavior
across PostgreSQL and Kafka. Payloads carry identifiers, hashes and small
results only. Evidence bodies, prompts, tool arguments and credentials are
forbidden from this envelope.

Schema evolution supports N/N-1. Removing a field, narrowing an accepted value
or changing field meaning requires a new event type major version and an
explicit migration/dual-publish window.

`examples/run-started.valid.json` and
`examples/untrusted-extra.invalid.json` are executable contract fixtures. Run
`npm run validate:events` from the parent `contracts` directory to compile the
Draft 2020-12 schema, accept the valid event, and prove that an unknown envelope
field and malformed tenant identifier are rejected.

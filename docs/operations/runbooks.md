# Failure runbooks

> Target production procedures. They depend on persistence, connector,
> authorization, retrieval and control-plane adapters that are not yet wired in
> v0.1, so they are review material rather than directly executable runbooks.

## External action timed out with an unknown result

1. Keep the receipt in `UNKNOWN_REQUIRES_RECONCILIATION`; do not replay the
   write merely because the transport timed out.
2. Query the source-specific verifier using the external request identifier and
   idempotency key.
3. If verification remains inconclusive, create a manual reconciliation task
   and block case completion.
4. Retry only after the connector proves the original write did not occur, or
   use the domain-defined compensator after an observed effect.

## Policy, verifier or data plane unavailable

1. Reject new execution admission and retain the proposed request for review.
2. Continue buffering bounded, signed observations where the connector permits
   it; never buffer credentials or unbounded evidence bodies.
3. Alert on dependency, queue-age and oldest-unverified-receipt metrics.
4. Re-evaluate policy, approval freshness and expected state version after
   recovery rather than resuming a stale admission decision.

## Qdrant unavailable or inconsistent

1. Degrade to authorized lexical/database retrieval; never remove ACL filters.
2. Stop candidate publication jobs that would leave partial index state.
3. Rebuild a new collection from PostgreSQL metadata and object evidence,
   including tenant, ACL, lifecycle and embedding-version payloads.
4. Compare counts and sampled hashes, atomically switch the collection alias,
   then retire the old collection.

## Tenant deletion

1. Freeze new work and revoke model/connector credentials.
2. Tombstone authoritative metadata, then purge tenant partitions, evidence
   objects, vector payloads, caches and buffered events according to policy.
3. Preserve only legally required, minimized audit receipts under a separate
   retention basis.
4. Produce a signed deletion report listing each store, count and completion
   timestamp; retry failed stores until reconciled.

## Restore and continuity

- Target control/runtime metadata RPO is five minutes and RTO is thirty
  minutes; validate with scheduled restore drills rather than backup-success
  signals alone.
- After PostgreSQL recovery, replay the outbox/inbox boundary, reconcile Kafka
  offsets, verify Temporal namespace state and rebuild derived Qdrant indexes.
- Do not mark actions successful from restored workflow state alone; the
  independent source verifier remains authoritative for outcomes.

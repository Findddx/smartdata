# ADR-0001: Evidence-backed order exception foundation

Status: Accepted

SmartData is an evidence-backed business action runtime, not a general chatbot or a replacement system of record. Source systems remain authoritative. The platform stores bitemporal claims, evidence references, decisions, approvals, action receipts, independent verification, and governed experience assets.

The first vertical slice is B2B manufacturing/distribution order-fulfilment exception handling. All writes require a typed action definition, server-side policy decision, human approval, idempotency key, connector receipt, and an independent verification result.

The runnable local profile uses deterministic simulators and in-memory adapters.
Production implementations must preserve the same ports and use PostgreSQL,
Kafka, Temporal, Qdrant, OpenFGA, and OPA; those adapters are not wired in v0.1.

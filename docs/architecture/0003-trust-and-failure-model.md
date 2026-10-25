# ADR-0003: Trust and failure model

Status: Accepted

## Authorities

- Source connectors provide observations, not infallible truth.
- The domain pack defines types and permitted capabilities.
- In production, verified identity plus OpenFGA/OPA must determine access and admission; v0.1 contains the identity boundary, policy port and policy examples but not those production adapters.
- A human approver authorizes v1 writes; the model never does.
- A verifier independently observes whether the intended business state exists.
- A business owner decides whether an experience candidate becomes shared memory.

## Failure semantics

- A stale aggregate or changed domain/policy version invalidates approval.
- Duplicate commands return the prior result only when their request fingerprint matches.
- An ambiguous external timeout becomes `UNKNOWN_REQUIRES_RECONCILIATION`; it is not retried as a fresh write.
- A second executor cannot classify a fresh `EXECUTING` record as abandoned: the persisted transition timestamp, action timeout and reconciliation grace form a minimum execution lease, while an in-process single-flight joins concurrent callers. The production repository must enforce the transition with database compare-and-set.
- An unavailable policy service, connector, or verifier fails closed for writes.
- A future vector adapter may degrade retrieval during an outage but must never relax tenant or asset authorization.
- Production cases and Temporal workflows must remain pinned to the versions with which they started. Workflow implementation v2 preserves the legacy v1 activity names/branch and validates bounded approval candidates through Activities; v0.1 has not yet connected API actions to Temporal.

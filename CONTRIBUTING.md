# Contributing

1. Keep domain logic in `foundation-kernel`; framework and transport details belong in adapters.
2. Every new write action must add a versioned schema, explicit roles, approval rule, idempotency behavior, independent verifier, failure semantics, and tests.
3. Domain-pack identifiers are stable. Breaking removals or action-schema changes require a new major version and a migration note.
4. Never commit secrets, raw customer evidence, production tokens, generated build output, or the ignored research cache.
5. Run JVM, frontend and Go tests before submitting changes. Dependency and lockfile changes require license and security review.

Use focused changes and explain the business invariant being protected. Generated artifacts must be reproducible from committed sources.

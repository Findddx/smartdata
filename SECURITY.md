# Security policy

## Supported versions

The project is pre-1.0. Security fixes are applied to the latest `main` revision. Production adopters must pin signed artifacts and dependency locks rather than floating branches.

## Reporting a vulnerability

Do not open a public issue containing exploit details, credentials, personal data, or customer topology. Use the repository host's private security-advisory channel. Include the affected revision, component, reproducible impact, and the smallest safe proof of concept; redact all real secrets and business records.

## Security boundary

The local profile contains public demo identities and deterministic synthetic data only. It is not an authentication configuration for production. A production deployment must provide a trusted OIDC issuer, mTLS between planes, externalized secrets, OpenFGA/OPA policy services, tenant-isolated storage, and TLS at the ingress.

The model is never an authorization or verification authority. A successful write requires server-derived identity, a typed action, policy admission, separation of requester and approver, an idempotency key, a connector receipt, and an independent verification result.

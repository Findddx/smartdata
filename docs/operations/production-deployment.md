# Production deployment baseline

The Helm chart is a security-conscious manifest starter, not a turnkey
production platform. It renders a runtime API workload and can render workflow
workers; the production API intentionally remains unready until real
persistence, authorization, policy, model and connector adapters are supplied.
Both the worker and MCP bridge are disabled until their production safety
prerequisites are met. Stateful dependencies remain customer-managed.

> **Execution safety gate:** `workflowWorker.enabled` and its Temporal switch
> both default to `false`. The current deterministic facade is suitable only
> for the local simulator and can report simulated outcomes. Do not enable the
> production worker until it is replaced by a connector-backed facade whose
> receipts and independent read-back verifier have passed contract, failure and
> reconciliation tests.

Before installation:

1. Replace every image tag with a registry digest and verify its signature and
   SBOM. The JVM Dockerfile layers the checksum-pinned official Temurin
   `25.0.4.1+1` JDK/JRE archives over a Noble bootstrap image until an
   equivalent immutable container tag is available; retain the checksum gate
   when replacing that bootstrap.
2. Create the chart's separate `runtimeApi.existingSecret` and
   `workflowWorker.existingSecret` through the cluster secret manager; never
   let the API and worker receive each other's unrelated credentials. Never
   commit a rendered Secret or credential-bearing values file.
3. Provide separate least-privilege database principals and databases for
   SmartData, Temporal and OpenFGA. Enable TLS, backups, point-in-time recovery
   and tested restores.
4. Configure OIDC/JWT verification at the runtime and gateway. The runtime
   requires `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`,
   `SMARTDATA_SECURITY_JWT_AUDIENCE`, and
   `SMARTDATA_SECURITY_JWT_CLIENT_ID`. Tenant and the dedicated
   `smartdata_roles` claim must come from that verified token; generic realm
   roles and client identity headers are ignored or rejected.
5. Load a reviewed OpenFGA model and the versioned OPA bundle. A failed policy,
   source, approval or verifier dependency must deny writes.
6. Configure `networkPolicy.egressCIDRs` narrowly. The default chart permits
   DNS, same-release SmartData workloads and bounded dependency ports in the
   configured `networkPolicy.dependencyNamespace`; external destinations remain
   denied until explicit CIDRs are supplied. Tighten the dependency rule with
   deployment-specific Pod labels, and prefer service identities/FQDN policy
   over broad CIDRs when the CNI supports them.
7. Supply service-to-service TLS/mTLS, trust bundles and certificate rotation.
   Do not enable the MCP HTTP escape hatch merely because its upstream is on a
   private network. The bridge's minimized DTOs do not replace a Runtime-side
   OPA export decision bound to destination, model, purpose and classification.
8. Keep the runtime's isolated Spring health probes enabled and verify that the
   customer's gateway does not publish the wider actuator surface.
9. Export redacted metrics and traces over OTLP. Prompt, tool arguments,
   evidence bodies, authorization headers and credentials remain disabled in
   telemetry by default.

Use an expand/contract database migration, N/N-1 protocol compatibility and
Temporal workflow version markers for each rolling release. Run the public
deterministic simulator suite, policy tests, connector contracts,
cross-tenant tests and restore drill before promotion. A separately governed
hidden acceptance set is still required before a real pilot.

The NetworkPolicy intentionally does not admit a public ingress controller.
Expose services only through the customer's authenticated gateway/service mesh
and add a narrowly scoped ingress policy owned by that platform team.

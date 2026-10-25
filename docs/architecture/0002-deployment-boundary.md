# ADR-0002: Hybrid management and runtime planes

Status: Accepted target decision

The management plane stores tenant configuration, signed domain-pack versions, release metadata, and redacted fleet health. Customer credentials, raw evidence, connectors, action execution, and sensitive model routing stay in a customer-local or region-local runtime plane.

Runtime nodes make outbound mTLS connections to the management plane. A disconnected node may buffer observations but must fail closed for new writes when policy, approval, source state, or verification cannot be established.

Implementation status: v0.1 defines the public Connector gRPC contract,
deployment topology and fail-closed boundaries. The management control plane,
customer Connector Node, outbound mTLS channel and durable disconnected-event
buffer are not implemented in this repository yet.

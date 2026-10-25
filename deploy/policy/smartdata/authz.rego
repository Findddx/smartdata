package smartdata.authz

import rego.v1

# This policy is deliberately fail-closed: absent or malformed input never grants.
default allow := false

read_scope contains "case:read"
read_scope contains "evidence:read"

valid_identity if {
	input.identity.authenticated == true
	is_string(input.identity.tenantId)
	input.identity.tenantId != ""
	is_string(input.identity.actorId)
	input.identity.actorId != ""
}

same_tenant if {
	is_string(input.resource.tenantId)
	input.resource.tenantId == input.identity.tenantId
}

base_authorized if {
	valid_identity
	same_tenant
	input.authorization.relationAllowed == true
	input.system.policyAvailable == true
}

allow if {
	base_authorized
	input.action.name in read_scope
}

# Creating an ActionRequest is not execution, but still requires a healthy local
# policy/source/verifier boundary so the request cannot imply unavailable safety.
allow if {
	base_authorized
	input.action.name == "action-request:create"
	input.system.dataPlaneConnected == true
	input.system.sourceAvailable == true
	input.system.verifierAvailable == true
	input.system.domainVersionCurrent == true
}

# The MCP bridge never exposes this scope. Runtime executors may use it only after
# a separate, current human approval and optimistic-state check.
allow if {
	base_authorized
	input.action.name == "action:execute"
	input.system.dataPlaneConnected == true
	input.system.sourceAvailable == true
	input.system.verifierAvailable == true
	input.system.domainVersionCurrent == true
	input.approval.valid == true
	input.approval.actorIsIndependent == true
	input.action.expectedStateVersionMatches == true
	input.action.hasIdempotencyKey == true
}

default decision := {
	"allow": false,
	"reason": "deny_fail_closed",
}

decision := {
	"allow": true,
	"reason": "allowed",
} if allow

package smartdata.authz_test

import data.smartdata.authz
import rego.v1

read_input := {
	"identity": {"authenticated": true, "tenantId": "tenant-a", "actorId": "actor-1"},
	"resource": {"tenantId": "tenant-a"},
	"authorization": {"relationAllowed": true},
	"system": {"policyAvailable": true},
	"action": {"name": "case:read"},
}

create_input := {
	"identity": {"authenticated": true, "tenantId": "tenant-a", "actorId": "actor-1"},
	"resource": {"tenantId": "tenant-a"},
	"authorization": {"relationAllowed": true},
	"system": {
		"policyAvailable": true,
		"dataPlaneConnected": true,
		"sourceAvailable": true,
		"verifierAvailable": true,
		"domainVersionCurrent": true,
	},
	"action": {"name": "action-request:create"},
}

test_read_allowed if {
	authz.allow with input as read_input
}

test_cross_tenant_read_denied if {
	not authz.allow with input as object.union(read_input, {"resource": {"tenantId": "tenant-b"}})
}

test_missing_relation_denied if {
	not authz.allow with input as object.remove(read_input, {"authorization"})
}

test_create_request_allowed_when_dependencies_healthy if {
	authz.allow with input as create_input
}

test_create_request_fails_closed_without_verifier if {
	broken_system := object.union(create_input.system, {"verifierAvailable": false})
	not authz.allow with input as object.union(create_input, {"system": broken_system})
}

test_execution_requires_approval_and_idempotency if {
	execution := object.union(create_input, {
		"action": {
			"name": "action:execute",
			"expectedStateVersionMatches": true,
			"hasIdempotencyKey": true,
		},
		"approval": {"valid": true, "actorIsIndependent": true},
	})
	authz.allow with input as execution
	not authz.allow with input as object.remove(execution, {"approval"})
}

test_unknown_action_denied if {
	not authz.allow with input as object.union(read_input, {"action": {"name": "payment:release"}})
}

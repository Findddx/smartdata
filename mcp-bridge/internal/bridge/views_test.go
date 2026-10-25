package bridge

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestCaseToolViewOmitsBusinessValuesAndSourceIdentifiers(t *testing.T) {
	view, err := minimizeCaseResponse(map[string]any{
		"id": "case-1", "titleZh": "异常", "priority": "HIGH", "status": "OPEN",
		"stateVersion": 2, "domainVersion": "domain@1", "policyVersion": "policy@1",
		"claims": []any{map[string]any{
			"predicate": "customerName", "value": "SECRET CUSTOMER", "subjectId": "SECRET SUBJECT",
			"status": "ASSERTED", "classification": "CONFIDENTIAL", "confidence": 1,
			"evidenceRefs": []any{map[string]any{
				"id": "evidence-1", "sourceSystem": "ERP", "sourceRecordId": "SECRET RECORD", "uri": "s3://secret",
			}},
		}},
	})
	if err != nil {
		t.Fatalf("minimizeCaseResponse() error = %v", err)
	}
	encoded, _ := json.Marshal(view)
	text := string(encoded)
	for _, secret := range []string{"SECRET CUSTOMER", "SECRET SUBJECT", "SECRET RECORD", "s3://secret"} {
		if strings.Contains(text, secret) {
			t.Fatalf("minimized view leaked %q: %s", secret, text)
		}
	}
	if len(view.ClaimDescriptors) != 1 || view.ClaimDescriptors[0].Predicate != "customerName" {
		t.Fatalf("unexpected descriptors: %#v", view.ClaimDescriptors)
	}
}

func TestActionRequestToolViewOmitsExecutionEnvelopeAndIdentity(t *testing.T) {
	view, err := minimizeActionRequestResponse(map[string]any{
		"id": "action-1", "definitionId": "reserve_substitute_inventory", "definitionVersion": "1.0.0",
		"state": "AWAITING_APPROVAL", "stateVersion": 2, "approvalExpiresAt": "2026-09-08T00:00:00Z",
		"caseId": "SECRET CASE", "requestedBy": "SECRET ACTOR", "idempotencyKey": "SECRET KEY",
		"input": map[string]any{"sku": "SECRET SKU"}, "evidenceIds": []string{"SECRET EVIDENCE"},
	})
	if err != nil {
		t.Fatalf("minimizeActionRequestResponse() error = %v", err)
	}
	encoded, _ := json.Marshal(view)
	text := string(encoded)
	for _, secret := range []string{"SECRET CASE", "SECRET ACTOR", "SECRET KEY", "SECRET SKU", "SECRET EVIDENCE"} {
		if strings.Contains(text, secret) {
			t.Fatalf("minimized action view leaked %q: %s", secret, text)
		}
	}
	if view.ID != "action-1" || view.State != "AWAITING_APPROVAL" {
		t.Fatalf("unexpected action view: %#v", view)
	}
}

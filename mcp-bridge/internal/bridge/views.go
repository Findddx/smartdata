package bridge

import (
	"encoding/json"
	"errors"
)

// MCP read models intentionally omit claim values, subject identifiers,
// object URIs and source record identifiers. User read access is necessary but
// not sufficient authority to export business data into an arbitrary model.
// A production deployment must additionally apply its destination-aware OPA
// egress policy before enabling the bridge.
type CaseToolView struct {
	ID               string                `json:"id"`
	TitleZh          string                `json:"titleZh"`
	Priority         string                `json:"priority"`
	Status           string                `json:"status"`
	ScenarioKinds    []string              `json:"scenarioKinds"`
	StateVersion     int64                 `json:"stateVersion"`
	DomainVersion    string                `json:"domainVersion"`
	PolicyVersion    string                `json:"policyVersion"`
	ClaimDescriptors []ClaimToolDescriptor `json:"claimDescriptors"`
	KnowledgeGaps    []GapToolView         `json:"knowledgeGaps"`
}

type ClaimToolDescriptor struct {
	Predicate      string   `json:"predicate"`
	Status         string   `json:"status"`
	Classification string   `json:"classification"`
	Confidence     float64  `json:"confidence"`
	SourceSystems  []string `json:"sourceSystems"`
	EvidenceIDs    []string `json:"evidenceIds"`
}

type GapToolView struct {
	Kind                string `json:"kind"`
	Code                string `json:"code"`
	DescriptionZh       string `json:"descriptionZh"`
	AffectedDecision    string `json:"affectedDecision"`
	RequiredObservation string `json:"requiredObservation"`
	DueAt               string `json:"dueAt,omitempty"`
}

type EvidenceToolView struct {
	ID             string `json:"id"`
	SourceSystem   string `json:"sourceSystem"`
	ContentHash    string `json:"contentHash"`
	ObservedAt     string `json:"observedAt"`
	RecordedAt     string `json:"recordedAt"`
	Classification string `json:"classification"`
}

// ActionRequestToolView is an acknowledgement, not a second copy of the
// privileged execution envelope. In particular it omits typed business input,
// evidence identifiers, identity data and the caller's idempotency key.
type ActionRequestToolView struct {
	ID                string `json:"id"`
	DefinitionID      string `json:"definitionId"`
	DefinitionVersion string `json:"definitionVersion"`
	State             string `json:"state"`
	StateVersion      int64  `json:"stateVersion"`
	ApprovalExpiresAt string `json:"approvalExpiresAt"`
}

type rawCase struct {
	ID            string   `json:"id"`
	TitleZh       string   `json:"titleZh"`
	Priority      string   `json:"priority"`
	Status        string   `json:"status"`
	ScenarioKinds []string `json:"scenarioKinds"`
	StateVersion  int64    `json:"stateVersion"`
	DomainVersion string   `json:"domainVersion"`
	PolicyVersion string   `json:"policyVersion"`
	Claims        []struct {
		Predicate      string  `json:"predicate"`
		Status         string  `json:"status"`
		Classification string  `json:"classification"`
		Confidence     float64 `json:"confidence"`
		EvidenceRefs   []struct {
			ID           string `json:"id"`
			SourceSystem string `json:"sourceSystem"`
		} `json:"evidenceRefs"`
	} `json:"claims"`
	KnowledgeGaps []GapToolView `json:"knowledgeGaps"`
}

func minimizeCaseResponse(value any) (CaseToolView, error) {
	encoded, err := json.Marshal(value)
	if err != nil {
		return CaseToolView{}, err
	}
	var raw rawCase
	if err := json.Unmarshal(encoded, &raw); err != nil {
		return CaseToolView{}, err
	}
	if raw.ID == "" || raw.Status == "" || raw.DomainVersion == "" || raw.PolicyVersion == "" {
		return CaseToolView{}, errors.New("runtime case response is missing required fields")
	}
	result := CaseToolView{
		ID:            raw.ID,
		TitleZh:       raw.TitleZh,
		Priority:      raw.Priority,
		Status:        raw.Status,
		ScenarioKinds: raw.ScenarioKinds,
		StateVersion:  raw.StateVersion,
		DomainVersion: raw.DomainVersion,
		PolicyVersion: raw.PolicyVersion,
		KnowledgeGaps: raw.KnowledgeGaps,
	}
	for _, claim := range raw.Claims {
		descriptor := ClaimToolDescriptor{
			Predicate:      claim.Predicate,
			Status:         claim.Status,
			Classification: claim.Classification,
			Confidence:     claim.Confidence,
		}
		for _, evidence := range claim.EvidenceRefs {
			descriptor.SourceSystems = appendUnique(descriptor.SourceSystems, evidence.SourceSystem)
			descriptor.EvidenceIDs = appendUnique(descriptor.EvidenceIDs, evidence.ID)
		}
		result.ClaimDescriptors = append(result.ClaimDescriptors, descriptor)
	}
	return result, nil
}

func minimizeEvidenceResponse(value any) ([]EvidenceToolView, error) {
	encoded, err := json.Marshal(value)
	if err != nil {
		return nil, err
	}
	var result []EvidenceToolView
	if err := json.Unmarshal(encoded, &result); err != nil {
		return nil, err
	}
	for _, evidence := range result {
		if evidence.ID == "" || evidence.SourceSystem == "" || evidence.ContentHash == "" || evidence.Classification == "" {
			return nil, errors.New("runtime evidence response is missing required fields")
		}
	}
	return result, nil
}

func minimizeActionRequestResponse(value any) (ActionRequestToolView, error) {
	encoded, err := json.Marshal(value)
	if err != nil {
		return ActionRequestToolView{}, err
	}
	var result ActionRequestToolView
	if err := json.Unmarshal(encoded, &result); err != nil {
		return ActionRequestToolView{}, err
	}
	if result.ID == "" || result.DefinitionID == "" || result.DefinitionVersion == "" ||
		result.State == "" || result.ApprovalExpiresAt == "" {
		return ActionRequestToolView{}, errors.New("runtime action request response is missing required fields")
	}
	return result, nil
}

func appendUnique(values []string, value string) []string {
	if value == "" {
		return values
	}
	for _, current := range values {
		if current == value {
			return values
		}
	}
	return append(values, value)
}

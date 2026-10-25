-- SmartData authoritative metadata schema. The application must execute
-- SET LOCAL app.tenant_id = '<verified tenant claim>' at transaction start.

CREATE TABLE tenant (
    id                  text PRIMARY KEY,
    display_name        text NOT NULL,
    status              text NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETING')),
    created_at          timestamptz NOT NULL
);

CREATE TABLE identity_link (
    tenant_id           text NOT NULL REFERENCES tenant(id),
    canonical_type      text NOT NULL,
    canonical_id        text NOT NULL,
    source_system       text NOT NULL,
    source_id           text NOT NULL,
    confidence          numeric(5,4) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    match_status        text NOT NULL CHECK (match_status IN ('MATCHED', 'ABSTAINED', 'REJECTED')),
    recorded_at         timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, source_system, source_id)
);

CREATE TABLE evidence_ref (
    tenant_id           text NOT NULL REFERENCES tenant(id),
    id                  uuid NOT NULL,
    source_system       text NOT NULL,
    source_record_id    text NOT NULL,
    content_hash        char(64) NOT NULL,
    object_uri          text,
    observed_at         timestamptz NOT NULL,
    recorded_at         timestamptz NOT NULL,
    classification      text NOT NULL,
    acl_ref             text NOT NULL,
    retention_until     timestamptz,
    PRIMARY KEY (tenant_id, id),
    CHECK (classification IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'))
);

CREATE TABLE claim (
    tenant_id           text NOT NULL REFERENCES tenant(id),
    id                  uuid NOT NULL,
    subject_type        text NOT NULL,
    subject_id          text NOT NULL,
    predicate           text NOT NULL,
    value_json          jsonb NOT NULL,
    status              text NOT NULL CHECK (status IN ('ASSERTED', 'INFERRED', 'CONFLICTED', 'REVOKED')),
    confidence          numeric(5,4) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    valid_from          timestamptz NOT NULL,
    valid_to            timestamptz,
    recorded_at         timestamptz NOT NULL,
    expires_at          timestamptz,
    classification      text NOT NULL,
    acl_ref             text NOT NULL,
    derivation_version  text,
    model_version       text,
    evidence_ids        uuid[] NOT NULL,
    PRIMARY KEY (tenant_id, id),
    CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CHECK (classification IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'))
);

CREATE INDEX claim_subject_time_idx
    ON claim (tenant_id, subject_type, subject_id, predicate, valid_from DESC, recorded_at DESC);
CREATE INDEX claim_expiry_idx ON claim (tenant_id, expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE exception_case (
    tenant_id           text NOT NULL REFERENCES tenant(id),
    id                  uuid NOT NULL,
    order_id            text NOT NULL,
    title_zh            text NOT NULL,
    priority            text NOT NULL CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    status              text NOT NULL CHECK (status IN ('OPEN', 'AWAITING_APPROVAL', 'IN_PROGRESS', 'VERIFIED_CLOSED', 'MANUAL_INTERVENTION')),
    scenario_kinds      text[] NOT NULL,
    domain_version      text NOT NULL,
    policy_version      text NOT NULL,
    state_version       bigint NOT NULL CHECK (state_version >= 0),
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id)
);

CREATE INDEX exception_case_queue_idx
    ON exception_case (tenant_id, status, priority, updated_at DESC);

CREATE TABLE knowledge_gap (
    tenant_id           text NOT NULL REFERENCES tenant(id),
    id                  uuid NOT NULL,
    case_id             uuid NOT NULL,
    kind                text NOT NULL CHECK (kind IN ('UNKNOWN', 'CONFLICTING', 'STALE')),
    code                text NOT NULL,
    subject_id          text NOT NULL,
    description_zh      text NOT NULL,
    affected_decision   text NOT NULL,
    required_observation text NOT NULL,
    due_at              timestamptz,
    classification      text NOT NULL,
    acl_ref              text NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, case_id) REFERENCES exception_case(tenant_id, id),
    CHECK (code ~ '^[A-Z][A-Z0-9_]{1,79}$'),
    CHECK (classification IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'))
);

CREATE TABLE recommendation (
    tenant_id           text NOT NULL REFERENCES tenant(id),
    id                  uuid NOT NULL,
    case_id             uuid NOT NULL,
    case_state_version  bigint NOT NULL,
    action_definition_id text NOT NULL,
    input_json          jsonb NOT NULL,
    rationale_zh        text NOT NULL,
    evidence_ids        uuid[] NOT NULL,
    domain_version      text NOT NULL,
    policy_version      text NOT NULL,
    model_version       text NOT NULL,
    created_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, case_id) REFERENCES exception_case(tenant_id, id)
);

CREATE TABLE action_request (
    tenant_id           text NOT NULL REFERENCES tenant(id),
    id                  uuid NOT NULL,
    case_id             uuid NOT NULL,
    recommendation_id   uuid NOT NULL,
    definition_id       text NOT NULL,
    definition_version  text NOT NULL,
    input_json          jsonb NOT NULL,
    evidence_ids        uuid[] NOT NULL,
    requested_by        text NOT NULL,
    approved_by         text,
    idempotency_key     text NOT NULL,
    request_fingerprint char(64) NOT NULL,
    expected_case_version bigint NOT NULL,
    bound_case_version  bigint NOT NULL,
    verification_bound_case_version bigint,
    domain_version      text NOT NULL,
    policy_version      text NOT NULL,
    state               text NOT NULL CHECK (state IN (
        'PROPOSED', 'POLICY_PASSED', 'AWAITING_APPROVAL', 'APPROVED', 'EXECUTING',
        'EXECUTED_UNVERIFIED', 'VERIFIED_SUCCEEDED', 'VERIFIED_FAILED', 'REJECTED',
        'EXPIRED', 'CANCELLED', 'UNKNOWN_REQUIRES_RECONCILIATION', 'COMPENSATING',
        'COMPENSATED', 'MANUAL_INTERVENTION'
    )),
    state_version       bigint NOT NULL CHECK (state_version >= 0),
    created_at          timestamptz NOT NULL,
    approval_expires_at timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, case_id) REFERENCES exception_case(tenant_id, id),
    FOREIGN KEY (tenant_id, recommendation_id) REFERENCES recommendation(tenant_id, id),
    CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),
    CHECK (approved_by IS NULL OR approved_by <> requested_by),
    CHECK (approval_expires_at > created_at)
);

CREATE INDEX action_request_queue_idx ON action_request (tenant_id, state, updated_at);

-- Approval is an immutable, version-bound business fact. Execution grants are
-- separately signed one-time capabilities; connectors must never infer
-- authority merely from action_request.state or approved_by.
CREATE TABLE approval_decision (
    tenant_id           text NOT NULL REFERENCES tenant(id),
    id                  uuid NOT NULL,
    action_request_id   uuid NOT NULL,
    decision            text NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    decided_by          text NOT NULL,
    action_state_version bigint NOT NULL CHECK (action_state_version >= 0),
    case_state_version  bigint NOT NULL CHECK (case_state_version >= 0),
    domain_version      text NOT NULL,
    policy_version      text NOT NULL,
    definition_version  text NOT NULL,
    input_sha256        char(64) NOT NULL,
    evidence_bundle_sha256 char(64) NOT NULL,
    reason_zh           text,
    decided_at          timestamptz NOT NULL,
    expires_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, action_request_id, action_state_version),
    FOREIGN KEY (tenant_id, action_request_id) REFERENCES action_request(tenant_id, id),
    CHECK (expires_at > decided_at)
);

CREATE TABLE connector_execution_grant (
    tenant_id           text NOT NULL REFERENCES tenant(id),
    jti                 uuid NOT NULL,
    action_request_id   uuid NOT NULL,
    approval_id         uuid NOT NULL,
    purpose             text NOT NULL CHECK (purpose IN ('ACTION', 'COMPENSATION')),
    grant_sha256        char(64) NOT NULL,
    authorization_context_sha256 char(64) NOT NULL,
    connector_id        text NOT NULL,
    issued_at           timestamptz NOT NULL,
    expires_at          timestamptz NOT NULL,
    consumed_at         timestamptz,
    PRIMARY KEY (tenant_id, jti),
    UNIQUE (tenant_id, action_request_id, purpose),
    FOREIGN KEY (tenant_id, action_request_id) REFERENCES action_request(tenant_id, id),
    FOREIGN KEY (tenant_id, approval_id) REFERENCES approval_decision(tenant_id, id),
    CHECK (expires_at > issued_at),
    CHECK (consumed_at IS NULL OR consumed_at >= issued_at)
);

CREATE TABLE action_receipt (
    tenant_id           text NOT NULL REFERENCES tenant(id),
    id                  uuid NOT NULL,
    action_request_id   uuid NOT NULL,
    external_request_id text NOT NULL,
    external_receipt_id text,
    outcome             text NOT NULL CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'UNKNOWN', 'COMPENSATED')),
    attempts_json       jsonb NOT NULL,
    compensation_receipt_id text,
    compensation_verification_id uuid,
    verification_id     uuid,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, action_request_id),
    FOREIGN KEY (tenant_id, action_request_id) REFERENCES action_request(tenant_id, id)
);

CREATE TABLE verification_result (
    tenant_id           text NOT NULL REFERENCES tenant(id),
    id                  uuid NOT NULL,
    action_request_id   uuid NOT NULL,
    verifier_id         text NOT NULL,
    purpose             text NOT NULL CHECK (purpose IN ('ACTION', 'COMPENSATION')),
    independent_source  text NOT NULL,
    outcome             text NOT NULL CHECK (outcome IN ('PASSED', 'FAILED', 'INCONCLUSIVE')),
    assertions_json     jsonb NOT NULL,
    evidence_ids        uuid[] NOT NULL,
    resolved_gap_ids    uuid[] NOT NULL,
    anti_gaming_metrics jsonb NOT NULL,
    observed_from       timestamptz NOT NULL,
    observed_until      timestamptz NOT NULL,
    created_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, action_request_id, purpose),
    FOREIGN KEY (tenant_id, action_request_id) REFERENCES action_request(tenant_id, id)
);

ALTER TABLE action_receipt
    ADD FOREIGN KEY (tenant_id, verification_id) REFERENCES verification_result(tenant_id, id),
    ADD FOREIGN KEY (tenant_id, compensation_verification_id) REFERENCES verification_result(tenant_id, id);

CREATE TABLE memory_asset (
    tenant_id           text NOT NULL REFERENCES tenant(id),
    id                  uuid NOT NULL,
    level               text NOT NULL CHECK (level IN ('L0_TRACE', 'L1_ATOMIC_CANDIDATE', 'L2_SCENARIO_PATTERN', 'L3_APPROVED_PLAYBOOK')),
    status              text NOT NULL CHECK (status IN ('PROPOSED', 'APPROVED', 'SUPERSEDED', 'REVOKED')),
    title_zh            text NOT NULL,
    content_zh          text NOT NULL,
    source_case_ids     uuid[] NOT NULL,
    source_evidence_ids uuid[] NOT NULL,
    valid_from          timestamptz NOT NULL,
    valid_to            timestamptz,
    expires_at          timestamptz,
    owner_id            text NOT NULL,
    acl_ref             text NOT NULL,
    model_version       text NOT NULL,
    embedding_version   text,
    supersedes_id       uuid,
    state_version       bigint NOT NULL CHECK (state_version >= 0),
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, supersedes_id) REFERENCES memory_asset(tenant_id, id),
    CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CHECK (expires_at IS NULL OR expires_at >= valid_from)
);

CREATE INDEX memory_retrieval_scope_idx
    ON memory_asset (tenant_id, status, level, updated_at DESC);

CREATE TABLE outbox_event (
    tenant_id           text NOT NULL REFERENCES tenant(id),
    event_id            uuid NOT NULL,
    aggregate_type      text NOT NULL,
    aggregate_id        text NOT NULL,
    event_type          text NOT NULL,
    event_version       integer NOT NULL,
    payload_json        jsonb NOT NULL,
    headers_json        jsonb NOT NULL,
    occurred_at         timestamptz NOT NULL,
    published_at        timestamptz,
    attempts            integer NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, event_id)
);

CREATE INDEX outbox_unpublished_idx ON outbox_event (occurred_at)
    WHERE published_at IS NULL;

CREATE TABLE inbox_event (
    tenant_id           text NOT NULL REFERENCES tenant(id),
    consumer_name       text NOT NULL,
    event_id            uuid NOT NULL,
    received_at         timestamptz NOT NULL,
    payload_hash        char(64) NOT NULL,
    PRIMARY KEY (tenant_id, consumer_name, event_id)
);

-- Every mutating HTTP command is journaled by tenant and key. The durable API
-- adapter writes this row atomically with the aggregate and outbox changes.
CREATE TABLE command_deduplication (
    tenant_id           text NOT NULL REFERENCES tenant(id),
    idempotency_key     text NOT NULL,
    command_type        text NOT NULL,
    request_fingerprint char(64) NOT NULL,
    authorization_context_sha256 char(64) NOT NULL,
    result_resource_type text NOT NULL,
    result_resource_id text NOT NULL,
    result_state_version bigint,
    created_at          timestamptz NOT NULL,
    expires_at          timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, idempotency_key),
    CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),
    CHECK (expires_at > created_at)
);

CREATE TABLE audit_event (
    tenant_id           text NOT NULL REFERENCES tenant(id),
    id                  uuid NOT NULL,
    aggregate_type      text NOT NULL,
    aggregate_id        text NOT NULL,
    event_type          text NOT NULL,
    actor_id            text NOT NULL,
    details_json        jsonb NOT NULL,
    previous_hash       char(64),
    event_hash          char(64) NOT NULL,
    occurred_at         timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, id)
);

CREATE INDEX audit_aggregate_idx
    ON audit_event (tenant_id, aggregate_type, aggregate_id, occurred_at);

-- Row-level security is defense in depth. The database role used by the
-- application must not have BYPASSRLS or table ownership in production.
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant
    USING (id = current_setting('app.tenant_id', true))
    WITH CHECK (id = current_setting('app.tenant_id', true));

DO $rls$
DECLARE
    table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'identity_link', 'evidence_ref', 'claim', 'exception_case', 'knowledge_gap',
        'recommendation', 'action_request', 'approval_decision', 'connector_execution_grant',
        'action_receipt', 'verification_result',
        'memory_asset', 'outbox_event', 'inbox_event', 'audit_event',
        'command_deduplication'
    ]
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_setting(''app.tenant_id'', true)) WITH CHECK (tenant_id = current_setting(''app.tenant_id'', true))',
            table_name
        );
    END LOOP;
END
$rls$;
